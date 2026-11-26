package io.github.totocastaldi.herdrdesktop.coreclient.agents

/** What the sidebar shows for an agent (REQ-AGT-001). */
enum class AgentState {
    /** At work. */
    WORKING,

    /** Waiting for the user: the program reported `blocked` or `error`. */
    BLOCKED,

    /** Finished a turn the user has not looked at yet. */
    DONE,

    /** Still, or finished and already seen. */
    IDLE
    ;

    companion object {
        /**
         * The shown state from the program's own word (OSC 7501 root record, null if it never said), the core's
         * turn counter and the last turn a person looked at (`herdr.seen`, null when the label is missing), with
         * the rules the Herdr clients share (`docs/labels.md` of herdr-app).
         */
        fun of(status: String?, turn: Long, seen: Long?): AgentState = when (status) {
            null -> IDLE

            "blocked", "error" -> BLOCKED

            "working" -> WORKING

            // Without the label the app's own rule applies: a reported `done` has not been seen.
            else -> if (seen == null) doneIf(status == "done") else doneIf(turn > seen)
        }

        private fun doneIf(unseen: Boolean) = if (unseen) DONE else IDLE
    }
}
