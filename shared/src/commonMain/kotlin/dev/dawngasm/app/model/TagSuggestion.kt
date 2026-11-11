package dev.dawngasm.app.model

import kotlinx.serialization.Serializable

/** One tag returned by the index autocomplete endpoint. */
@Serializable
data class TagSuggestion(
    val tag: String,
    val count: Long,
)
