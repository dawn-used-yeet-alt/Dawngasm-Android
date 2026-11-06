package dev.dawngasm.app.state

import dev.dawngasm.app.model.Track

data class PlayerUiState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
)

enum class NavigationTab { Home, Library, Settings }

enum class LibrarySubTab { History, Saved, Downloads }
