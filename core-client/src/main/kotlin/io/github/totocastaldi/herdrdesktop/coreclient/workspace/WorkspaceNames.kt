package io.github.totocastaldi.herdrdesktop.coreclient.workspace

/** Names and starting folders of spaces and tabs (REQ-WS-001). */
object WorkspaceNames {
    /** Name shown for [space]: its own name, else the folder of its first terminal, else its folder. */
    fun displayName(space: Space, cwdOf: (String) -> String?): String? {
        space.name.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val first = space.tabs.firstNotNullOfOrNull { it.terminals.firstOrNull() }
        val folder = first?.let(cwdOf)?.takeIf { it.isNotBlank() } ?: space.folder.takeIf { it.isNotBlank() }
        return folder?.let(::folderName)
    }

    /** Name shown for [tab]: its own name, else its 1-based position, as in the desktop app. */
    fun tabLabel(tab: Tab, index: Int): String = tab.name.trim().ifEmpty { (index + 1).toString() }

    /** Last component of [path]; `/` stays `/`. */
    fun folderName(path: String): String {
        val trimmed = path.trimEnd('/')
        return if (trimmed.isEmpty()) "/" else trimmed.substringAfterLast('/')
    }

    /**
     * Folder a new tab of [space] starts in: the current folder of the active terminal, else the space
     * folder, else null (the core's default). [activeTerminal] defaults to the first terminal of [activeTab].
     */
    fun newTabFolder(
        space: Space,
        activeTab: Tab?,
        cwdOf: (String) -> String?,
        activeTerminal: String? = null
    ): String? {
        val terminal = activeTerminal ?: activeTab?.terminals?.firstOrNull()
        return terminal?.let(cwdOf)?.takeIf { it.isNotBlank() } ?: space.folder.takeIf { it.isNotBlank() }
    }

    /** A name as stored: trimmed, blank meaning unnamed. */
    fun normalize(name: String): String = name.trim()
}
