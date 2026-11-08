package dev.dawngasm.app.state

import dev.dawngasm.app.model.SearchRequest
import dev.dawngasm.app.model.Track

data class AppUiState(
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
    val saved: List<Track> = emptyList(),
    val history: List<Track> = emptyList(),
    val downloads: List<Track> = emptyList(),
    val downloadingIds: Set<String> = emptySet(),
    val player: PlayerUiState = PlayerUiState(),
    val query: String = "",
    val includeTags: List<String> = emptyList(),
    val excludeTags: List<String> = emptyList(),
    val sort: String = "top",
    val window: String = "week",
    val hosts: List<String> = emptyList(),
    val excludeHosts: List<String> = emptyList(),
    val blockedTags: List<String> = emptyList(),
    val downloadTreeUri: String? = null,
    val downloadFolderLabel: String = "App storage (Music/Dawngasm)",
    val hotaudioConnected: Boolean = false,
    val audiochanConnected: Boolean = false,
) {
    val savedIds: Set<String> get() = saved.mapTo(HashSet()) { it.id }
    val downloadedIds: Set<String> get() = downloads.mapTo(HashSet()) { it.id }

    val searchRequest: SearchRequest
        get() = SearchRequest(
            text = query,
            includeTags = includeTags,
            excludeTags = excludeTags,
            sort = sort,
            window = window,
            hosts = hosts,
            excludeHosts = excludeHosts,
        )

    val searchSummary: String
        get() {
            val request = searchRequest
            val queryText = request.summary()
            return if (queryText.isEmpty()) {
                ""
            } else {
                "$queryText · ${request.filtersLabel()}"
            }
        }
}

data class UiMessage(
    val text: String,
    val isError: Boolean = false,
)
