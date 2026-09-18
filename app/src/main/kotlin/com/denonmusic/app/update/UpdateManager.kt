package com.denonmusic.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Where the app looks for updates. Each GitHub Release must be tagged with the version name
 * (e.g. `v0.1.0`) and have the release-signed `DenonMusic-vX.Y.Z.apk` attached as an asset -
 * Android only installs an update signed with the same key as the current install.
 */
object UpdateConfig {
    const val OWNER = "hkrob"
    const val REPO = "denonmusic"

    /**
     * `api.github.com` enforces a 60-requests/hour limit per source IP for unauthenticated callers -
     * shared across every device behind the same NAT gateway, which on a home or mobile connection can
     * be many unrelated users. That quota being exhausted surfaced in this app as the update check
     * failing with no useful reason ("Couldn't reach GitHub, or the latest release has no APK") even
     * though the release existed. The public releases Atom feed is served from the plain `github.com`
     * web tier, not `api.github.com`, so it isn't subject to that quota, and it carries both the tag
     * name and the release notes in one request - see [UpdateManager.parseLatestFeedEntry].
     */
    val latestReleaseFeedUrl: String
        get() = "https://github.com/$OWNER/$REPO/releases.atom"

    /** The asset name `.github/workflows/release.yml` always publishes a release's APK under. */
    fun apkUrl(tag: String) = "https://github.com/$OWNER/$REPO/releases/download/$tag/DenonMusic-$tag.apk"
}

data class ReleaseInfo(
    val versionName: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val notes: String,
)

/**
 * Self-update over GitHub Releases: check the latest published version, download its APK, and hand
 * it to the system installer. No third-party libraries - HttpURLConnection + JDK XML parsing, same
 * shape as this project's own [com.denonmusic.app.lancontrol.LanControlServer] and [com.denonmusic.smb.SmbOverlay].
 */
object UpdateManager {
    private const val TIMEOUT_MS = 15_000

    /** Latest published release, or null if unreachable / no APK asset. Runs off the main thread. */
    suspend fun checkLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val conn = (URL(UpdateConfig.latestReleaseFeedUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "DenonMusic") // GitHub rejects requests without a UA
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        val entry = try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            parseLatestFeedEntry(conn.inputStream) ?: return@withContext null
        } finally {
            conn.disconnect()
        }
        val versionName = entry.tag.trim().removePrefix("v").removePrefix("V")
        val apkUrl = UpdateConfig.apkUrl(entry.tag)
        val apkSizeBytes = headContentLength(apkUrl) ?: return@withContext null
        ReleaseInfo(
            versionName = versionName,
            apkUrl = apkUrl,
            apkSizeBytes = apkSizeBytes,
            notes = renderReleaseNotes(entry.notesHtml),
        )
    }

    /** The most recent `<entry>` of the releases Atom feed: its tag name and raw HTML notes body. */
    internal data class FeedEntry(val tag: String, val notesHtml: String)

    /**
     * The feed lists releases newest-first, one `<entry>` per release, with the tag name as its
     * `<title>` and the release notes (this project's own changelog bullets, rendered to HTML by
     * GitHub) as its `<content>`. `DocumentBuilderFactory` is plain JDK, not an Android class, so this
     * parses under a plain JVM unit test with no Robolectric needed.
     */
    internal fun parseLatestFeedEntry(xml: InputStream): FeedEntry? {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val entry = doc.getElementsByTagName("entry").item(0) as? Element ?: return null
        val tag = (entry.getElementsByTagName("title").item(0)?.textContent ?: return null).trim()
        val notesHtml = entry.getElementsByTagName("content").item(0)?.textContent ?: ""
        return FeedEntry(tag, notesHtml)
    }

    /**
     * Turns the feed's `<content>` HTML (always a `<ul>` of `<li>` bullets for this project's own
     * changelog - see `release.yml`'s notes-extraction step) back into the same "- bullet" plain-text
     * shape the old GitHub API's raw markdown `body` field used to hand `AboutScreen` directly.
     * Falls back to stripping tags outright for any release note that isn't a plain bullet list.
     */
    internal fun renderReleaseNotes(html: String): String {
        val items = Regex("<li>(.*?)</li>", RegexOption.DOT_MATCHES_ALL).findAll(html)
            .map { unescapeHtml(it.groupValues[1].trim()) }
            .toList()
        if (items.isNotEmpty()) return items.joinToString("\n") { "- $it" }
        return unescapeHtml(html.replace(Regex("<[^>]+>"), " ")).trim()
    }

    private fun unescapeHtml(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&") // must run last so it doesn't re-unescape the entities above

    /** The APK's size via `HEAD`, or null if the asset doesn't actually exist at [apkUrl]. */
    private fun headContentLength(apkUrl: String): Long? {
        val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            instanceFollowRedirects = true // GitHub redirects asset downloads to a CDN host
            setRequestProperty("User-Agent", "DenonMusic")
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null else conn.contentLengthLong.coerceAtLeast(0L)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * True when [latest] is a strictly higher version than [current]. Compares dotted numbers
     * component-wise (0.10 > 0.9, unlike a string compare), tolerating a leading "v" and any
     * non-numeric suffix on a component (e.g. this project's own "-debug" versionNameSuffix).
     */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parseVersion(latest)
        val b = parseVersion(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parseVersion(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .split(".")
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    /** Downloads the release APK into cache/updates, reporting 0..100 progress. Returns the file. */
    suspend fun download(context: Context, release: ReleaseInfo, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "DenonMusic-${release.versionName}.apk")
            val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true // GitHub redirects asset downloads to a CDN host
                setRequestProperty("User-Agent", "DenonMusic")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            try {
                conn.connect()
                val total = if (release.apkSizeBytes > 0) release.apkSizeBytes else conn.contentLengthLong
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var read: Int
                        var lastPct = -1
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = ((downloaded * 100) / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            out
        }

    /** Launches the system package installer for [file] (user confirms the install). */
    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Whether the app is currently allowed to install APKs ("install unknown apps"). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user grants this app permission to install APKs. */
    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
