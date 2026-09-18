package com.denonmusic.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Tiny in-memory cache so navigating away and back doesn't refetch the same album art every time. */
private object ArtworkCache {
    val byUrl = ConcurrentHashMap<String, Bitmap>()
}

/**
 * Loads [url] (a plain HTTP image, e.g. HEOS/Plex's own `image_url`) and shows it, or nothing at all
 * while pending or on failure - no placeholder image bundled just for this. Hand-rolled rather than
 * pulling in an image-loading library for the one thing this app needs from one.
 */
// ProduceStateDoesNotAssignValue misfires here: `value` is assigned on every path, but the check
// only recognises assignments it can see at the top level of the producer lambda, and these are
// inside an `if`/`let` with early returns and behind a `withContext`. Verified by reducing the
// producer to a single top-level `value = null`, which the check then accepted.
@Suppress("ProduceStateDoesNotAssignValue")
@Composable
fun RemoteArtwork(url: String?, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val bitmap by produceState<Bitmap?>(initialValue = url?.let { ArtworkCache.byUrl[it] }, key1 = url) {
        if (url.isNullOrBlank()) {
            value = null
            return@produceState
        }
        ArtworkCache.byUrl[url]?.let {
            value = it
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching { URL(url).openStream().use { stream -> BitmapFactory.decodeStream(stream) } }.getOrNull()
        }?.also { ArtworkCache.byUrl[url] = it }
    }
    bitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = contentDescription, modifier = modifier) }
}

/** Same idea as [RemoteArtwork] but for bytes already in hand - the bridge path's own file-side read. */
@Suppress("ProduceStateDoesNotAssignValue") // Same false positive as above.
@Composable
fun LocalArtwork(bytes: ByteArray?, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = bytes) {
        value = bytes?.let { b ->
            withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull() }
        }
    }
    bitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = contentDescription, modifier = modifier) }
}
