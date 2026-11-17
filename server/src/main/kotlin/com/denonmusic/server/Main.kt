package com.denonmusic.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import kotlin.system.exitProcess

fun main() {
    val config = try {
        Config.fromEnvironment()
    } catch (e: IllegalStateException) {
        System.err.println("denonmusic-server: ${e.message}")
        exitProcess(2)
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val receiver = Receiver(config)
    val index = LibraryIndex(
        file = File(config.dataDir, "library-index.json"),
        scope = scope,
        clientProvider = { receiver.heos.heosClient },
        sourceSid = receiver::sourceSid,
    )
    index.load()
    receiver.start()

    // The index is never built unasked: a crawl is tens of minutes of browse traffic at the
    // receiver, and when that is acceptable is the owner's call. POST /index/rebuild starts one.
    println("denonmusic-server: receiver ${config.receiverHost}, index ${index.status.state} (${index.status.count} entries)")

    val server = embeddedServer(CIO, port = config.httpPort) { api(config, Controller(config, receiver, index)) }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
            receiver.stop()
            scope.cancel()
        },
    )
    server.start(wait = true)
}
