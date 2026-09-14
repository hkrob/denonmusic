package com.denonmusic.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Where the app looks for updates. Each GitHub Release must be tagged with the version name
 * (e.g. `v0.1.0`) and have the release-signed `DenonMusic-vX.Y.Z.apk` attached as an asset -
 * Android only installs an update signed with the same key as the current install.
 */
object UpdateConfig {
    const val OWNER = "hkrob"
    const val REPO = "denonmusic"

    val latestReleaseApiUrl: String
        get() = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
}

data class ReleaseInfo(
    val versionName: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val notes: String,
)

/**
 * Self-update over GitHub Releases: check the latest published version, download its APK, and hand
 * it to the system installer. No third-party libraries - HttpURLConnection + org.json, same shape as
 * this project's own [com.denonmusic.app.lancontrol.LanControlServer] and [com.denonmusic.smb.SmbOverlay].
 */
object UpdateManager {
    private const val TIMEOUT_MS = 15_000

    /** Latest published release, or null if unreachable / no APK asset. Runs off the main thread. */
    suspend fun checkLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val conn = (URL(UpdateConfig.latestReleaseApiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "DenonMusic") // GitHub rejects requests without a UA
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val obj = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val versionName = obj.getString("tag_name").trim().removePrefix("v").removePrefix("V")
            val notes = obj.optString("body", "")
            val assets = obj.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk", ignoreCase = true)) {
                    return@withContext ReleaseInfo(
                        versionName = versionName,
                        apkUrl = a.getString("browser_download_url"),
                        apkSizeBytes = a.optLong("size", 0L),
                        notes = notes,
                    )
                }
            }
            null
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
