package dev.dawngasm.app.data

import dev.dawngasm.app.model.Track

/**
 * Manages the current playback queue, tracking the current item index and
 * computing next/previous track navigation transitions without unexpected wrapping.
 */
class PlaybackQueue(
    initialTracks: List<Track> = emptyList(),
    initialCurrent: Track? = null,
) {
    var tracks: List<Track> = initialTracks
        private set

    var currentTrack: Track? = initialCurrent
        private set

    val currentIndex: Int
        get() = currentTrack?.let { curr -> tracks.indexOfFirst { it.id == curr.id } } ?: -1

    val hasNext: Boolean
        get() = currentIndex in 0 until tracks.size - 1

    val hasPrevious: Boolean
        get() = currentTrack != null

    fun setQueue(newTracks: List<Track>, startingTrack: Track) {
        tracks = if (newTracks.any { it.id == startingTrack.id }) {
            newTracks
        } else {
            listOf(startingTrack) + newTracks
        }
        currentTrack = startingTrack
    }

    fun getNextTrack(): Track? {
        val idx = currentIndex
        return if (idx in 0 until tracks.size - 1) {
            tracks[idx + 1]
        } else {
            null
        }
    }

    /**
     * Determines the previous track transition.
     * If more than 3 seconds have been played, or if already at the first track,
     * restarts the current track instead of wrapping around to the end.
     *
     * @return Pair of (targetTrack, shouldSeekToZeroOnly)
     */
    fun getPreviousTrack(currentPositionMs: Long): Pair<Track?, Boolean> {
        val current = currentTrack ?: return Pair(null, false)
        if (currentPositionMs > 3_000L) {
            return Pair(current, true)
        }
        val idx = currentIndex
        return if (idx > 0) {
            Pair(tracks[idx - 1], false)
        } else {
            // At the first track: restart it instead of wrapping to the last track
            Pair(current, true)
        }
    }

    fun moveToNext(): Track? {
        val next = getNextTrack()
        if (next != null) {
            currentTrack = next
        }
        return next
    }

    fun clear() {
        tracks = emptyList()
        currentTrack = null
    }
}
