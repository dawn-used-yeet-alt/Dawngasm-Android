package dev.dawngasm.app.model

/** One tag returned by the index autocomplete endpoint. */
data class TagSuggestion(
    val tag: String,
    val count: Long,
)
