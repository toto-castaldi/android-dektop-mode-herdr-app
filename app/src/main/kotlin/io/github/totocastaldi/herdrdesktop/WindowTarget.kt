package io.github.totocastaldi.herdrdesktop

/**
 * What a new window of the app opens (REQ-DM-001): the machine and the space it shows first.
 * Every window keeps its own selection; spaces, tabs and terminals stay on the core and are shared.
 */
data class WindowTarget(val machineId: String?, val spaceId: String?) {
    companion object {
        const val EXTRA_MACHINE = "io.github.totocastaldi.herdrdesktop.extra.MACHINE"
        const val EXTRA_SPACE = "io.github.totocastaldi.herdrdesktop.extra.SPACE"

        /**
         * The space a new window opens on, from the window showing [current] among [spaceIds]:
         * the next one (the first after the last), so that the two windows show different spaces when the
         * machine has more than one; null without spaces.
         */
        fun otherSpace(spaceIds: List<String>, current: String?): String? {
            if (spaceIds.isEmpty()) return null
            val index = spaceIds.indexOf(current)
            return if (index < 0) spaceIds.first() else spaceIds[(index + 1) % spaceIds.size]
        }
    }
}
