package dev.dawngasm.app.data

import dev.dawngasm.app.model.AudioHost
import dev.dawngasm.app.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueTest {

    private fun testTrack(id: String, title: String) = Track(
        id = id,
        title = title,
        author = "author",
        host = AudioHost.SOUNDGASM,
        pageUrl = "https://soundgasm.net/u/author/$id",
    )

    @Test
    fun emptyQueueHasNoNextOrPrevious() {
        val queue = PlaybackQueue()
        assertFalse(queue.hasNext)
        assertFalse(queue.hasPrevious)
        assertNull(queue.currentTrack)
        assertNull(queue.getNextTrack())
        val (prev, seekToZero) = queue.getPreviousTrack(0L)
        assertNull(prev)
        assertFalse(seekToZero)
    }

    @Test
    fun setQueueSetsTracksAndCurrentTrack() {
        val t1 = testTrack("1", "Track 1")
        val t2 = testTrack("2", "Track 2")
        val queue = PlaybackQueue()
        queue.setQueue(listOf(t1, t2), t1)

        assertEquals(t1, queue.currentTrack)
        assertEquals(0, queue.currentIndex)
        assertTrue(queue.hasNext)
        assertTrue(queue.hasPrevious)
    }

    @Test
    fun nextTrackStopsAtEndOfQueueWithoutWrapping() {
        val t1 = testTrack("1", "Track 1")
        val t2 = testTrack("2", "Track 2")
        val queue = PlaybackQueue()
        queue.setQueue(listOf(t1, t2), t1)

        assertTrue(queue.hasNext)
        val next = queue.moveToNext()
        assertEquals(t2, next)
        assertEquals(t2, queue.currentTrack)
        assertEquals(1, queue.currentIndex)

        // At end of queue: hasNext must be false and getNextTrack must return null
        assertFalse(queue.hasNext)
        assertNull(queue.getNextTrack())
        assertNull(queue.moveToNext())
        assertEquals(t2, queue.currentTrack)
    }

    @Test
    fun previousTrackRestartsWhenPlayedMoreThanThreeSeconds() {
        val t1 = testTrack("1", "Track 1")
        val t2 = testTrack("2", "Track 2")
        val queue = PlaybackQueue()
        queue.setQueue(listOf(t1, t2), t2)

        val (target, shouldSeekToZeroOnly) = queue.getPreviousTrack(5_000L)
        assertEquals(t2, target)
        assertTrue(shouldSeekToZeroOnly)
    }

    @Test
    fun previousTrackNavigatesToPrecedingTrackWhenUnderThreeSeconds() {
        val t1 = testTrack("1", "Track 1")
        val t2 = testTrack("2", "Track 2")
        val queue = PlaybackQueue()
        queue.setQueue(listOf(t1, t2), t2)

        val (target, shouldSeekToZeroOnly) = queue.getPreviousTrack(1_500L)
        assertEquals(t1, target)
        assertFalse(shouldSeekToZeroOnly)
    }

    @Test
    fun previousTrackAtFirstTrackRestartsInsteadOfWrapping() {
        val t1 = testTrack("1", "Track 1")
        val t2 = testTrack("2", "Track 2")
        val queue = PlaybackQueue()
        queue.setQueue(listOf(t1, t2), t1)

        // When at track 0 under 3s, it restarts track 0 rather than jumping to track 2
        val (target, shouldSeekToZeroOnly) = queue.getPreviousTrack(1_000L)
        assertEquals(t1, target)
        assertTrue(shouldSeekToZeroOnly)
    }
}
