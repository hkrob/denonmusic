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
     * Album art for [path]: folder art next to it if present (the plan's preferred source - one
     * directory listing covers art for every track in the folder, see [findFolderArtwork]), else an
     * embedded picture read straight out of the file's own tags. FLAC and MP3 only -
     * [ArtworkExtractor] has no MP4/M4A support, so an Atmos rip falls back to folder art or nothing.
     */
    fun findArtwork(path: String): ByteArray? {
        findFolderArtwork(path.substringBeforeLast('/', ""))?.let { return it }
        val file = SmbFile(smbUrl(path), context)
        return runCatching {
            file.inputStream.use { stream ->
                when (path.substringAfterLast('.', "").lowercase()) {
                    "flac" -> ArtworkExtractor.extractFlacPicture(stream)
                    "mp3" -> ArtworkExtractor.extractId3Apic(stream)
                    else -> null
                }
            }
        }.getOrNull()
    }

    /**
     * Lists [directoryPath] (empty string for the share root): subfolders first, then files whose
     * extension one of the plan's format parsers actually recognises, both in natural order (see
     * [naturalOrderComparator] - a real 10-disc box set on the share this was verified against sorts
     * as `Disc 1, Disc 10, Disc 2, ...` under a plain string sort, which would queue it in the wrong
     * order via [listFilesRecursive]). Filters out anything else (`.nfo`, artwork, playlists, ...)
     * since this listing exists to pick something to play, not to be a general-purpose file manager.
     */
    fun listDirectory(directoryPath: String): List<SmbEntry>? {
        val normalized = if (directoryPath.isEmpty() || directoryPath.endsWith("/")) directoryPath else "$directoryPath/"
        val dir = SmbFile(smbUrl(normalized), context)
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return null
        val (folders, files) = children.partition { runCatching { it.isDirectory }.getOrDefault(false) }
        val folderEntries = folders
            .filterNot { it.name.startsWith(".") }
            .map { SmbEntry(name = it.name.removeSuffix("/"), path = normalized + it.name.removeSuffix("/"), isDirectory = true) }
            .sortedWith(compareBy(naturalOrderComparator) { it.name })
        val fileEntries = files
            .filter { isSupportedAudioFile(it.name) }
            .map { SmbEntry(name = it.name, path = normalized + it.name, isDirectory = false) }
            .sortedWith(compareBy(naturalOrderComparator) { it.name })
        return folderEntries + fileEntries
    }

    /**
     * Every playable file under [directoryPath], walking into subfolders too - what a multi-disc
     * album needs (an `Album` folder holding `CD1` and `CD2` subfolders, no tracks at the album's own
     * level), which [listDirectory] alone can't reach since it only ever lists one level. Depth-first,
     * each level alphabetical, so disc order comes out right without relying on any naming
     * convention. Bounded by [MAX_RECURSE_DEPTH] against a pathological share layout.
     */
    fun listFilesRecursive(directoryPath: String, depth: Int = 0): List<SmbEntry> {
        if (depth > MAX_RECURSE_DEPTH) return emptyList()
        val entries = listDirectory(directoryPath) ?: return emptyList()
        val (folders, files) = entries.partition { it.isDirectory }
        return files + folders.flatMap { listFilesRecursive(it.path, depth + 1) }
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
        // 512KB rather than the 64KB every other parser here needs, to give Mp4HeaderParser's
        // `moov`/`stsd` walk enough room on a file with a larger-than-usual `moov` (multiple codec
        // variants, extra metadata atoms) - still a trivial read over a LAN SMB share.
        private const val HEADER_READ_LIMIT_BYTES = 512 * 1024
        private val FOLDER_ART_NAMES = listOf("folder.jpg", "cover.jpg")
        private const val MAX_RECURSE_DEPTH = 6
    }
}

/**
 * Case-insensitive natural-order comparator: digit runs compare by numeric value, not
 * digit-by-digit, so `Disc 2` sorts before `Disc 10`. A plain string sort doesn't - verified against
 * a real 10-disc box set on the share this project targets, where it produced `Disc 1, Disc 10, Disc
 * 2, ..., Disc 9`.
 */
internal val naturalOrderComparator = Comparator<String> { a, b -> naturalCompare(a.lowercase(), b.lowercase()) }

internal fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var iEnd = i
            while (iEnd < a.length && a[iEnd].isDigit()) iEnd++
            var jEnd = j
            while (jEnd < b.length && b[jEnd].isDigit()) jEnd++
            val numA = a.substring(i, iEnd).trimStart('0').ifEmpty { "0" }
            val numB = b.substring(j, jEnd).trimStart('0').ifEmpty { "0" }
            val cmp = if (numA.length != numB.length) numA.length - numB.length else numA.compareTo(numB)
            if (cmp != 0) return cmp
            i = iEnd
            j = jEnd
        } else {
            if (ca != cb) return ca.compareTo(cb)
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
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
