package com.denonmusic.smb

import java.io.InputStream
import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile

data class SmbCredentials(val host: String, val share: String, val username: String, val password: String)

data class SmbFileStat(val path: String, val mtime: Long, val size: Long)

/** One row of a directory listing - a subfolder, or a file the plan's format list recognises. */
data class SmbEntry(val name: String, val path: String, val isDirectory: Boolean)

/**
 * Read-only jcifs-ng overlay onto the same share HEOS is (or will be) indexing. Never writes
 * anything - this app is a controller, and per the plan the phone is never in the audio path (except
 * for the phase-6 bridge fallback in [openBridgeResource], the one deliberate exception); this
 * overlay exists purely to read header bytes and folder art HEOS's own metadata doesn't carry.
 *
 * Verified against a real unraid share (2026-09-13) - see docs/local-setup.md.
 */
class SmbOverlay(private val credentials: SmbCredentials) {

    private val context: CIFSContext by lazy {
        SingletonContext.getInstance()
            .withCredentials(NtlmPasswordAuthenticator("", credentials.username, credentials.password))
    }

    private fun smbUrl(path: String): String {
        val trimmed = path.removePrefix("/")
        return "smb://${credentials.host}/${credentials.share}/$trimmed"
    }

    fun stat(path: String): SmbFileStat? {
        val file = SmbFile(smbUrl(path), context)
        if (!file.exists()) return null
        return SmbFileStat(path = path, mtime = file.lastModified(), size = file.length())
    }

    /** Reads at most the first [HEADER_READ_LIMIT_BYTES] of [path] and parses it by extension. */
    fun parseFormat(path: String): AudioFormatInfo? {
        val file = SmbFile(smbUrl(path), context)
        return file.inputStream.use { stream ->
            AudioHeaderParser.parse(path, BoundedInputStream(stream, HEADER_READ_LIMIT_BYTES))
        }
    }

    /**
     * `folder.jpg`/`cover.jpg` next to the file, checked in that order - one directory listing
     * covers every track in the folder, which is why the plan prefers this over decoding an embedded
     * picture per track.
     */
    fun findFolderArtwork(directoryPath: String): ByteArray? {
        val normalized = if (directoryPath.endsWith("/")) directoryPath else "$directoryPath/"
        val dir = SmbFile(smbUrl(normalized), context)
        val entries = runCatching { dir.listFiles() }.getOrNull() ?: return null
        val match = FOLDER_ART_NAMES.firstNotNullOfOrNull { name ->
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        } ?: return null
        return match.inputStream.use { it.readBytes() }
    }

    /**
     * Lists [directoryPath] (empty string for the share root): subfolders first, then files whose
     * extension one of the plan's format parsers actually recognises, both alphabetical. Filters out
     * anything else (`.nfo`, artwork, playlists, ...) since this listing exists to pick something to
     * play, not to be a general-purpose file manager.
     */
    fun listDirectory(directoryPath: String): List<SmbEntry>? {
        val normalized = if (directoryPath.isEmpty() || directoryPath.endsWith("/")) directoryPath else "$directoryPath/"
        val dir = SmbFile(smbUrl(normalized), context)
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return null
        val (folders, files) = children.partition { runCatching { it.isDirectory }.getOrDefault(false) }
        val folderEntries = folders
            .filterNot { it.name.startsWith(".") }
            .map { SmbEntry(name = it.name.removeSuffix("/"), path = normalized + it.name.removeSuffix("/"), isDirectory = true) }
            .sortedBy { it.name.lowercase() }
        val fileEntries = files
            .filter { isSupportedAudioFile(it.name) }
            .map { SmbEntry(name = it.name, path = normalized + it.name, isDirectory = false) }
            .sortedBy { it.name.lowercase() }
        return folderEntries + fileEntries
    }

    fun openStream(path: String): InputStream = SmbFile(smbUrl(path), context).inputStream

    /**
     * Backs [SmbBridgeServer]'s phase-6 fallback: stats [path] once for its length/content-type, then
     * hands back a resource that reopens the file and skips to any requested offset on demand, so one
     * `BridgeResource` can answer both a plain GET and a ranged one.
     */
    fun openBridgeResource(path: String): BridgeResource? {
        val stat = stat(path) ?: return null
        return BridgeResource(
            length = stat.size,
            contentType = contentTypeForPath(path),
            openAt = { offset ->
                val stream = openStream(path)
                skipFully(stream, offset)
                stream
            },
        )
    }

    companion object {
        private const val HEADER_READ_LIMIT_BYTES = 64 * 1024
        private val FOLDER_ART_NAMES = listOf("folder.jpg", "cover.jpg")
    }
}

/** No dependency was pulled in just for this: a fixed byte ceiling on an [InputStream]. */
private class BoundedInputStream(private val delegate: InputStream, private val limit: Int) : InputStream() {
    private var readCount = 0

    override fun read(): Int {
        if (readCount >= limit) return -1
        val b = delegate.read()
        if (b != -1) readCount++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (readCount >= limit) return -1
        val toRead = minOf(len, limit - readCount)
        val n = delegate.read(b, off, toRead)
        if (n > 0) readCount += n
        return n
    }

    override fun close() = delegate.close()
}

/** `InputStream.skipNBytes` needs API 31; this project's minSdk is 26. */
private fun skipFully(input: InputStream, count: Long) {
    var remaining = count
    while (remaining > 0) {
        val skipped = input.skip(remaining)
        if (skipped <= 0) {
            if (input.read() == -1) throw java.io.EOFException()
            remaining--
        } else {
            remaining -= skipped
        }
    }
}
