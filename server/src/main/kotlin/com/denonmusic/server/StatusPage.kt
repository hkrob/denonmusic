package com.denonmusic.server

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The page at `/`, for a person who opens the server in a browser. Like `/health` it needs no
 * token, so it shows only what `/health` and `GET /index` would: whether the server can see the
 * receiver and the state of the index. Nothing about what is playing, and no receiver address.
 */
object StatusPage {

    private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")

    fun render(
        heos: String,
        avr: String,
        index: IndexStatus,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val rows = StringBuilder()
        fun at(millis: Long?) = millis?.let { WHEN.format(Instant.ofEpochMilli(it).atZone(zone)) }
        fun row(label: String, value: String, good: Boolean? = null) {
            val mark = when (good) {
                true -> """<span class="dot ok"></span>"""
                false -> """<span class="dot bad"></span>"""
                null -> ""
            }
            rows.append("<tr><th>${escape(label)}</th><td>$mark${escape(value)}</td></tr>\n")
        }
        row("HEOS (port 1255)", heos, heos == "Connected")
        row("Receiver control (port 23)", avr, avr == "Connected")
        row("Library index", index.state, index.state == "ready")
        row("Entries", "%,d".format(index.count))
        at(index.indexedAt)?.let { row("Last built", it) }
        index.found?.let { row("Found so far", "%,d".format(it)) }
        index.error?.let { row("Last error", it, false) }
        row("Next scheduled rebuild", at(index.nextScheduledAt) ?: "none scheduled")
        return PAGE.replace("{{rows}}", rows.toString()).replace("{{zone}}", escape(zone.id))
    }

    private fun escape(text: String) = buildString {
        text.forEach {
            when (it) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(it)
            }
        }
    }

    private val PAGE = """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="refresh" content="30">
        <title>denonmusic server</title>
        <style>
          :root { --bg: #f6f6f4; --card: #ffffff; --text: #1d1d1b; --muted: #6b6b66; --line: #e4e4df;
                  --ok: #2e8b57; --bad: #c0392b; color-scheme: light dark; }
          @media (prefers-color-scheme: dark) {
            :root { --bg: #161615; --card: #20201f; --text: #ececea; --muted: #9a9a94; --line: #33332f;
                    --ok: #4caf7a; --bad: #e0675a; }
          }
          body { margin: 0; background: var(--bg); color: var(--text);
                 font: 15px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
          main { max-width: 34rem; margin: 2.5rem auto; padding: 0 16px; }
          h1 { font-size: 1.3rem; margin: 0 0 .25rem; }
          p { color: var(--muted); margin: 0 0 1.25rem; }
          table { width: 100%; border-collapse: collapse; background: var(--card);
                  border: 1px solid var(--line); border-radius: 10px; overflow: hidden; }
          th, td { text-align: left; padding: .6rem .9rem; border-top: 1px solid var(--line); vertical-align: top; }
          tr:first-child th, tr:first-child td { border-top: 0; }
          th { font-weight: 500; color: var(--muted); width: 45%; }
          .dot { display: inline-block; width: .55rem; height: .55rem; border-radius: 50%; margin-right: .45rem; }
          .ok { background: var(--ok); } .bad { background: var(--bad); }
          footer { color: var(--muted); font-size: .85rem; margin-top: 1rem; }
          code { font-size: .85em; }
        </style>
        </head>
        <body>
        <main>
          <h1>denonmusic server</h1>
          <p>Queues and controls the receiver for Home Assistant. Refreshes every 30 seconds.</p>
          <table>
        {{rows}}  </table>
          <footer>Times are {{zone}}. Machine-readable: <code>/health</code>. Every other route needs the API token.</footer>
        </main>
        </body>
        </html>
    """.trimIndent() + "\n"
}
