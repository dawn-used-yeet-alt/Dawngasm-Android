package dev.dawngasm.app.model

/**
 * One complete search, combining free text with tag filters and feed order.
 *
 * Tag selections are kept separate from [text] so the index can use its
 * structured `tags` / `excludeTags` parameters instead of relying on text
 * matching inside titles.
 */
data class SearchRequest(
    val text: String = "",
    val includeTags: List<String> = emptyList(),
    val excludeTags: List<String> = emptyList(),
    val sort: String = SearchSyntax.DEFAULT_SORT,
    val window: String = SearchSyntax.DEFAULT_WINDOW,
    val hosts: List<String> = emptyList(),
    val excludeHosts: List<String> = emptyList(),
) {
    /** Human-readable query, e.g. `rain [f4m] -[m4f] host:soundgasm -host:whyp`. */
    fun summary(): String = buildList {
        text.trim().takeIf { it.isNotEmpty() }?.let(::add)
        includeTags.forEach { add("[$it]") }
        excludeTags.forEach { add("-[${it}]") }
        hosts.forEach { add("host:$it") }
        excludeHosts.forEach { add("-host:$it") }
    }.joinToString(" ")

    fun filtersLabel(): String =
        "${SearchSyntax.sortLabel(sort)} · ${SearchSyntax.windowLabel(window)}"

    fun hasFilters(): Boolean =
        summary().isNotEmpty() ||
            sort != SearchSyntax.DEFAULT_SORT ||
            window != SearchSyntax.DEFAULT_WINDOW ||
            hosts.isNotEmpty() ||
            excludeHosts.isNotEmpty()
}
