package com.denonmusic.app.bridge

import com.denonmusic.heos.RepeatMode
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class BridgeQueueLogicTest {

    private fun items(vararg names: String) = names.map { BridgeQueueItem(path = it, displayName = it) }

    @Test
    fun `replaceQueue starts at the given index`() {
        val state = BridgeQueueLogic.replaceQueue(BridgeQueueState(), items("a", "b", "c"), startIndex = 1)

        assertEquals("b", state.currentItem?.path)
    }

    @Test
    fun `addToEnd appends without disturbing the current index`() {
        val playing = BridgeQueueLogic.replaceQueue(BridgeQueueState(), items("a", "b"), startIndex = 1)

        val state = BridgeQueueLogic.addToEnd(playing, items("c"))

        assertEquals(listOf("a", "b", "c"), state.items.map { it.path })
        assertEquals("b", state.currentItem?.path)
    }

    @Test
    fun `addToEnd on an empty queue starts playback at the first item`() {
        val state = BridgeQueueLogic.addToEnd(BridgeQueueState(), items("a", "b"))

        assertEquals("a", state.currentItem?.path)
    }

    @Test
    fun `next advances sequentially and stops at the end with repeat off`() {
        val state = BridgeQueueLogic.replaceQueue(BridgeQueueState(), items("a", "b"), startIndex = 0)

        val afterFirst = BridgeQueueLogic.next(state)
        assertEquals("b", afterFirst?.currentItem?.path)

        val afterLast = BridgeQueueLogic.next(afterFirst!!)
        assertNull(afterLast)
    }

    @Test
    fun `next wraps around with repeat all`() {
        val state = BridgeQueueLogic.replaceQueue(BridgeQueueState(), items("a", "b"), startIndex = 1)
            .let { BridgeQueueLogic.setRepeat(it, RepeatMode.All) }

        val wrapped = BridgeQueueLogic.next(state)

        assertEquals("a", wrapped?.currentItem?.path)
    }

    @Test
    fun `next replays the same track with repeat one`() {
        val state = BridgeQueueLogic.replaceQueue(BridgeQueueState(), items("a", "b"), startIndex = 0)
            .let { BridgeQueueLogic.setRepeat(it, RepeatMode.One) }

        val result = BridgeQueueLogic.next(state)

        assertEquals("a", result?.currentItem?.path)
        assertEquals(0, result?.currentIndex)
    }

    @Test
    fun `previous steps back and stops before the start with repeat off`() {
        val state = BridgeQueueLogic.replaceQueue(BridgeQueueState(), items("a", "b"), startIndex = 1)

        val back = BridgeQueueLogic.previous(state)
        assertEquals("a", back?.currentItem?.path)

        val beforeStart = BridgeQueueLogic.previous(back!!)
        assertNull(beforeStart)
    }

    @Test
    fun `shuffle visits every track exactly once before repeating`() {
        var state = BridgeQueueLogic.replaceQueue(BridgeQueueState(), items("a", "b", "c"), startIndex = 0)
        state = BridgeQueueLogic.toggleShuffle(state)

        val seen = mutableSetOf(state.currentItem!!.path)
        repeat(2) {
            state = BridgeQueueLogic.next(state, random = { 0.999 })!!
            seen += state.currentItem!!.path
        }

        assertEquals(setOf("a", "b", "c"), seen)
        // Queue exhausted with repeat off - one more call has nothing left to offer.
        assertNull(BridgeQueueLogic.next(state, random = { 0.0 }))
    }

    @Test
    fun `shuffle with repeat all starts a fresh pass once exhausted instead of stopping`() {
        var state = BridgeQueueLogic.replaceQueue(BridgeQueueState(), items("a", "b"), startIndex = 0)
        state = BridgeQueueLogic.toggleShuffle(state)
        state = BridgeQueueLogic.setRepeat(state, RepeatMode.All)

        state = BridgeQueueLogic.next(state, random = { 0.999 })!! // visits the only remaining track - queue now exhausted
        val afterExhaustion = BridgeQueueLogic.next(state, random = { 0.0 })

        assertEquals(true, afterExhaustion != null)
        assertEquals(true, afterExhaustion!!.currentItem?.path in listOf("a", "b"))
    }

    @Test
    fun `removeAt shifts the current index down when removing an earlier item`() {
        val state = BridgeQueueLogic.replaceQueue(BridgeQueueState(), items("a", "b", "c"), startIndex = 2)

        val result = BridgeQueueLogic.removeAt(state, 0)

        assertEquals(listOf("b", "c"), result.items.map { it.path })
        assertEquals("c", result.currentItem?.path)
    }

    @Test
    fun `removeAt the current item clamps to the new last index`() {
        val state = BridgeQueueLogic.replaceQueue(BridgeQueueState(), items("a", "b"), startIndex = 1)

        val result = BridgeQueueLogic.removeAt(state, 1)

        assertEquals(listOf("a"), result.items.map { it.path })
        assertEquals("a", result.currentItem?.path)
    }

    @Test
    fun `removeAt the only item leaves an empty queue`() {
        val state = BridgeQueueLogic.replaceQueue(BridgeQueueState(), items("a"), startIndex = 0)

        val result = BridgeQueueLogic.removeAt(state, 0)

        assertEquals(emptyList(), result.items)
        assertEquals(-1, result.currentIndex)
    }
}
