package com.denonmusic.smb

import java.io.InputStream
import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile

data class SmbCredentials(val host: String, val share: String, val username: String, val password: String)

data class SmbFileStat(val path: String, val mtime: Long, val size: Long)

/**
 * Read-only jcifs-ng overlay onto the same share HEOS is (or will be) indexing. Never writes
 * anything - this app is a controller, and per the plan the phone is never in the audio path; this
 * overlay exists purely to read header bytes and folder art HEOS's own metadata doesn't carry.
 *
 * **Unverified against a real share as of this writing.** No SMB/DLNA source has been registered on
 * the HEOS system this project was built against (see docs/local-setup.md), so this class has only
 * ever been exercised in a JVM unit test sense - the parsers it delegates to are thoroughly tested,
 * but jcifs-ng's own network path, and this class's use of it, has not touched a real server.
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

    fun openStream(path: String): InputStream = SmbFile(smbUrl(path), context).inputStream

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
