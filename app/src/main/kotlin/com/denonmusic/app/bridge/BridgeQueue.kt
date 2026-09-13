package com.denonmusic.app.bridge

import com.denonmusic.heos.RepeatMode
import com.denonmusic.smb.AudioFormatInfo

/** One entry in the phase-6 bridge's own client-side queue - real HEOS queueing doesn't apply to `play_stream`. */
data class BridgeQueueItem(val path: String, val displayName: String)

data class BridgeQueueState(
    val items: List<BridgeQueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val repeat: RepeatMode = RepeatMode.Off,
    val shuffle: Boolean = false,
    /** Shuffle-only: indices already played since the last exhaustion or shuffle toggle, so a shuffled pass doesn't repeat a track before covering the rest of the queue. */
    val playedShuffleIndices: Set<Int> = emptySet(),
    /** Populated asynchronously once [currentIndex]'s header has been read; null while pending. */
    val currentFormatInfo: AudioFormatInfo? = null,
) {
    val currentItem: BridgeQueueItem? get() = items.getOrNull(currentIndex)
}

/**
 * Pure queue-advance rules for the bridge fallback: since `play_stream` starts exactly one stream
 * with no real queue behind it, "next track" has to be reimplemented client-side. Kept as pure
 * functions over an explicit state (rather than mutable fields on the controller that owns the HEOS
 * side effects) so the actual sequencing logic - the part with real edge cases (repeat-one, shuffle
 * exhaustion, wrap-around) - can be unit tested without a receiver or an event socket.
 */
object BridgeQueueLogic {

    fun replaceQueue(current: BridgeQueueState, items: List<BridgeQueueItem>, startIndex: Int): BridgeQueueState =
        BridgeQueueState(
            items = items,
            currentIndex = startIndex.coerceIn(items.indices),
            repeat = current.repeat,
            shuffle = current.shuffle,
        )

    fun addToEnd(current: BridgeQueueState, items: List<BridgeQueueItem>): BridgeQueueState {
        val merged = current.items + items
        // Starting an "add to queue" with nothing playing yet should start playback, matching the
        // primary HEOS browse screen's own add_to_queue behaviour on an empty queue.
        val newIndex = if (current.currentIndex == -1 && merged.isNotEmpty()) 0 else current.currentIndex
        return current.copy(items = merged, currentIndex = newIndex)
    }

    fun removeAt(current: BridgeQueueState, index: Int): BridgeQueueState {
        if (index !in current.items.indices) return current
        val newItems = current.items.toMutableList().apply { removeAt(index) }
        val newIndex = when {
            newItems.isEmpty() -> -1
            index < current.currentIndex -> current.currentIndex - 1
            index == current.currentIndex -> current.currentIndex.coerceAtMost(newItems.lastIndex)
            else -> current.currentIndex
        }
        return current.copy(items = newItems, currentIndex = newIndex, playedShuffleIndices = emptySet())
    }

    fun setRepeat(current: BridgeQueueState, mode: RepeatMode): BridgeQueueState = current.copy(repeat = mode)

    fun toggleShuffle(current: BridgeQueueState): BridgeQueueState =
        current.copy(shuffle = !current.shuffle, playedShuffleIndices = emptySet())

    /** Null means "nothing more to play" - the caller should stop rather than start a new stream. */
    fun next(current: BridgeQueueState, random: () -> Double = Math::random): BridgeQueueState? {
        if (current.items.isEmpty() || current.currentIndex == -1) return null
        if (current.repeat == RepeatMode.One) return current
        return if (current.shuffle) nextShuffled(current, random) else nextSequential(current)
    }

    /** Shuffle has no well-defined "previous"; steps back sequentially regardless. */
    fun previous(current: BridgeQueueState): BridgeQueueState? {
        if (current.items.isEmpty() || current.currentIndex == -1) return null
        val p = current.currentIndex - 1
        return when {
            p >= 0 -> current.copy(currentIndex = p)
            current.repeat == RepeatMode.All -> current.copy(currentIndex = current.items.lastIndex)
            else -> null
        }
    }

    private fun nextSequential(current: BridgeQueueState): BridgeQueueState? {
        val n = current.currentIndex + 1
        return when {
            n <= current.items.lastIndex -> current.copy(currentIndex = n)
            current.repeat == RepeatMode.All -> current.copy(currentIndex = 0)
            else -> null
        }
    }

    private fun nextShuffled(current: BridgeQueueState, random: () -> Double): BridgeQueueState? {
        val played = current.playedShuffleIndices + current.currentIndex
        var remaining = current.items.indices.filter { it !in played }
        var playedForResult = played
        if (remaining.isEmpty()) {
            if (current.repeat != RepeatMode.All) return null
            playedForResult = emptySet()
            remaining = current.items.indices.filter { it != current.currentIndex }
            if (remaining.isEmpty()) remaining = current.items.indices.toList()
        }
        val pickIndex = (random() * remaining.size).toInt().coerceIn(remaining.indices)
        val pick = remaining[pickIndex]
        return current.copy(currentIndex = pick, playedShuffleIndices = playedForResult)
    }
}
