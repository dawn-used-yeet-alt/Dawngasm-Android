package dev.dawngasm.app.model

import kotlinx.serialization.Serializable

/** One author returned by the index autocomplete endpoint. */
@Serializable
data class AuthorSuggestion(
    val author: String,
    val count: Long,
)
