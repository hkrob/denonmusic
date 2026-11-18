package com.denonmusic.server

import com.denonmusic.heos.AddCriteria
import com.denonmusic.heos.HeosCommandException
import com.denonmusic.heos.PlayState
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLDecodeException
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.install
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

private val json = Json { ignoreUnknownKeys = true }

/** An intent is a few short strings; anything bigger than this is not one. */
private const val MAX_BODY_BYTES = 8 * 1024L

/**
 * The HTTP surface. Route names and parameters follow the Android app's LAN control API where the
 * two overlap (`/status`, `/queue`, `/play`, `/volume?level=`, the `X-Lan-Control-Token` header), so
 * a client written for one needs no translation for the other. `/search`, `/intent` and `/index`
 * are the server's own.
 */
fun Application.api(config: Config, controller: Controller) {
    install(FailureResponses)
    routing {
        // Unauthenticated on purpose: a container health check has no token, and this says nothing
        // beyond whether the server can see the receiver.
        get("/health") { call.respondJson(HttpStatusCode.OK, controller.health()) }
        // For a person with a browser: the same facts as /health and GET /index, as a page.
        get("/") { call.respondText(controller.statusPage(), ContentType.Text.Html) }

        get("/status") { call.api(config) { controller.status() } }

        get("/search") {
            call.api(config) { params ->
                val query = params["q"]?.takeIf { it.isNotBlank() } ?: throw ApiException(400, "missing 'q'")
                controller.search(query, params["artist"], params["type"], params.int("limit") ?: 10, params["prefer"])
            }
        }
        post("/intent") {
            call.api(config) {
                if ((call.request.contentLength() ?: 0) > MAX_BODY_BYTES) throw ApiException(413, "body too large")
                val body = call.receiveText()
                if (body.length > MAX_BODY_BYTES) throw ApiException(413, "body too large")
                val request = try {
                    json.decodeFromString<IntentRequest>(body)
                } catch (e: SerializationException) {
                    throw ApiException(400, """expected a JSON body like {"action":"play","query":"..."}""")
                }
                listOfNotNull(request.action, request.query, request.artist, request.type).forEach(::rejectControlCharacters)
                controller.intent(request)
            }
        }

        get("/queue") { call.api(config) { controller.queue() } }
        post("/queue/add") {
            call.api(config) { params ->
                val sid = params["sid"] ?: throw ApiException(400, "missing 'sid'")
                val known = AddCriteria.entries.joinToString { it.name }
                val criteria = AddCriteria.entries.firstOrNull { it.name.equals(params["criteria"], ignoreCase = true) }
                    ?: throw ApiException(400, "missing or invalid 'criteria', expected one of $known")
                val queued = controller.addToQueue(sid, params["cid"], params["mid"], criteria)
                buildJsonObject {
                    put("ok", true)
                    put("queued", queued)
                }
            }
        }
        post("/queue/play") { call.api(config) { params -> controller.playQueueItem(params.requiredInt("qid")) } }
        post("/queue/clear") { call.api(config) { controller.clearQueue() } }

        post("/play") { call.api(config) { controller.setPlayState(PlayState.Play) } }
        post("/pause") { call.api(config) { controller.setPlayState(PlayState.Pause) } }
        post("/stop") { call.api(config) { controller.setPlayState(PlayState.Stop) } }
        post("/next") { call.api(config) { controller.next() } }
        post("/previous") { call.api(config) { controller.previous() } }

        post("/volume") {
            call.api(config) { params ->
                val level = params.int("level")
                val delta = params.int("delta")
                when {
                    level != null -> controller.setVolume(level)
                    delta != null -> controller.changeVolume(delta)
                    else -> throw ApiException(400, "missing 'level' or 'delta'")
                }
            }
        }
        post("/mute") { call.api(config) { params -> controller.setMute(params.requiredBool("on")) } }
        post("/power") { call.api(config) { params -> controller.setPower(params.requiredBool("on")) } }

        get("/index") { call.api(config) { controller.indexStatus() } }
        post("/index/rebuild") { call.api(config) { controller.rebuildIndex() } }
    }
}

/**
 * Answers for a failure that never reached a route. Found against the running server, not by
 * reading: Ktor decodes the query string while it is still choosing a route, so `?q=%zz` threw
 * before [api] below had a chance to catch it - a bare 500 and a stack trace in the log, for anyone
 * who can reach the port. The app's LAN server died outright on the same input until 0.1.33.
 */
private val FailureResponses = createApplicationPlugin("FailureResponses") {
    on(CallFailed) { call, cause ->
        if (cause is URLDecodeException) {
            call.respondError(HttpStatusCode.BadRequest, "bad request")
        } else {
            call.respondError(HttpStatusCode.InternalServerError, "internal error")
        }
    }
}

/**
 * Checks the token, then the parameters, then runs [block], turning whatever it throws into a JSON
 * error with a status that says whose fault it was.
 *
 * The order matters for the same reason it did in the app's LAN server: everything before the token
 * check runs for anyone who can reach the port, so nothing before it parses caller input.
 */
private suspend fun ApplicationCall.api(config: Config, block: suspend (Parameters) -> JsonElement) {
    if (!authorised(config.apiToken)) return respondError(HttpStatusCode.Unauthorized, "unauthorized")
    try {
        val params = request.queryParameters
        // Both receiver protocols are line-based and neither escapes a line ending, so a CR in a
        // `cid` would reach the HEOS port as a second command. Nothing this API accepts needs one.
        params.forEach { _, values -> values.forEach(::rejectControlCharacters) }
        respondJson(HttpStatusCode.OK, block(params))
    } catch (e: TimeoutCancellationException) {
        // Before the CancellationException it is a subclass of: this is the receiver not answering a
        // command, not this request being cancelled. Rethrown as a cancellation it reached the
        // caller as a bare "internal error" - seen against the real unit, on a get_queue that took
        // twenty seconds.
        respondError(HttpStatusCode.GatewayTimeout, "the receiver did not answer in time")
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        respondError(HttpStatusCode.fromValue(e.status), e.message ?: "error")
    } catch (e: HeosCommandException) {
        // The receiver answered, and said no.
        respondError(HttpStatusCode.BadGateway, e.message ?: "the receiver refused the command")
    } catch (e: java.io.IOException) {
        respondError(HttpStatusCode.ServiceUnavailable, "lost the connection to the receiver: ${e.message}")
    } catch (e: Exception) {
        respondError(HttpStatusCode.InternalServerError, e.message ?: "internal error")
    }
}

private fun rejectControlCharacters(value: String) {
    if (value.any(Char::isISOControl)) throw ApiException(400, "bad request")
}

/** Header only - a `?token=` would end up in every access log and shell history between here and HA. */
private fun ApplicationCall.authorised(expected: String): Boolean {
    val presented = request.header("X-Lan-Control-Token")
        ?: request.header("Authorization")?.removePrefix("Bearer ")?.trim()
        ?: return false
    // Constant-time, so response timing can't leak how much of a guess was right.
    return MessageDigest.isEqual(presented.toByteArray(), expected.toByteArray())
}

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: JsonElement) =
    respondText(body.toString(), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) =
    respondJson(status, buildJsonObject { put("error", message) })

private fun Parameters.int(name: String): Int? =
    this[name]?.let { it.toIntOrNull() ?: throw ApiException(400, "'$name' must be a whole number") }

private fun Parameters.requiredInt(name: String): Int = int(name) ?: throw ApiException(400, "missing '$name'")

private fun Parameters.requiredBool(name: String): Boolean = when (this[name]?.lowercase()) {
    "true", "on", "1" -> true
    "false", "off", "0" -> false
    else -> throw ApiException(400, "missing or invalid '$name' (true/false)")
}
