package com.denonmusic.server

import com.denonmusic.avr.FakeAvrServer
import com.denonmusic.heos.FakeHeosServer
import com.denonmusic.heos.HeosProtocol
import java.io.Closeable

/**
 * Both control ports of a receiver, on loopback, answering just enough for the server to connect and
 * find its player. Tests add whatever else they need to [heos] and [avr].
 */
class FakeReceiver : Closeable {
    val heos = FakeHeosServer()
    val avr = FakeAvrServer()

    init {
        heos.onSuccess("system/register_for_change_events")
        heos.onSuccess("system/heart_beat")
        heos.onSuccess("player/get_players", payload = """[{"name":"AVR","pid":"7","model":"Denon AVR-X4500H"}]""")
        heos.onSuccess(
            "browse/get_music_sources",
            payload = """[{"name":"Local Music","type":"heos_server","sid":"1024","available":"true"}]""",
        )
    }

    fun config(dataDir: java.io.File, maxVolume: Int = 60) = Config(
        receiverHost = heos.host,
        apiToken = TOKEN,
        dataDir = dataDir,
        maxVolume = maxVolume,
        heosPort = heos.port,
        avrPort = avr.port,
    )

    /** Serves a browse tree keyed by cid (null for the source root), as `LibraryIndexerTest` does. */
    fun serveTree(tree: Map<String?, List<String>>) {
        heos.on("browse/browse") { line ->
            val args = HeosProtocol.parseMessage(line.substringAfter('?', ""))
            val items = tree[args["cid"]].orEmpty()
            listOf(
                """{"heos":{"command":"browse/browse","result":"success",""" +
                    """"message":"sid=1024&returned=${items.size}&count=${items.size}""" +
                    """&${HeosProtocol.SEQUENCE}=${FakeHeosServer.sequenceArgOf(line)}"},""" +
                    """"payload":[${items.joinToString(",")}]}""",
            )
        }
    }

    /** Commands received, as `group/command` plus the arguments named in [show], heartbeats aside. */
    fun sent(vararg show: String): List<String> = heos.received.mapNotNull { line ->
        val path = line.removePrefix("heos://").substringBefore('?')
        if (path in QUIET) return@mapNotNull null
        val args = HeosProtocol.parseMessage(line.substringAfter('?', ""))
        (listOf(path) + show.mapNotNull { name -> args[name]?.let { "$name=$it" } }).joinToString(" ")
    }

    override fun close() {
        heos.close()
        avr.close()
    }

    companion object {
        const val TOKEN = "test-token"
        private val QUIET = setOf("system/register_for_change_events", "system/heart_beat", "player/get_players")

        fun track(name: String, mid: String, artist: String = "", album: String = "") =
            """{"container":"no","playable":"yes","type":"song","name":"$name","mid":"$mid","artist":"$artist","album":"$album"}"""

        fun folder(name: String, cid: String) =
            """{"container":"yes","playable":"yes","type":"container","name":"$name","cid":"$cid"}"""
    }
}

/** Polls for a condition the server reaches on its own schedule - a connection, a finished crawl. */
fun awaitTrue(what: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
        Thread.sleep(20)
    }
}
