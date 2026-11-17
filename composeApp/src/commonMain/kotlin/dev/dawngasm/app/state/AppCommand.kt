package dev.dawngasm.app.state

/**
 * Shell-level commands that change UI navigation rather than app data. Desktop
 * menus and keyboard accelerators emit these; the shared UI consumes them.
 */
enum class AppCommand {
    OpenSearch,
    ShowHome,
    ShowLibrary,
    ShowSettings,
    Refresh,
    DismissPlayer,
}
