package com.simone.jarvismobile.audio

/**
 * Where a typed chat question should be answered from, chosen with the mode
 * picker in the chat. [ALL] is the normal assistant (tools + model + every
 * source); [MEMORY] answers only from the user's saved memory and imported
 * documents; [KNOWLEDGE] answers only from the offline library (wiki/guides).
 *
 * The mode travels with the message through the existing persisted response
 * queue as a short prefix tag, so no database column has to change. The tag is
 * stripped before the message is shown or answered.
 */
enum class ChatFocus {
    ALL,
    MEMORY,
    KNOWLEDGE,
    ;

    /** Wraps [text] with this mode's tag so the worker can route it later. */
    fun tag(text: String): String = when (this) {
        ALL -> text
        MEMORY -> MEM_TAG + text
        KNOWLEDGE -> WIKI_TAG + text
    }

    companion object {
        private const val MEM_TAG = "@@focus:mem@@ "
        private const val WIKI_TAG = "@@focus:wiki@@ "

        /** Splits a queued input back into its mode and the clean user text. */
        fun parse(raw: String): Pair<ChatFocus, String> = when {
            raw.startsWith(MEM_TAG) -> MEMORY to raw.removePrefix(MEM_TAG)
            raw.startsWith(WIKI_TAG) -> KNOWLEDGE to raw.removePrefix(WIKI_TAG)
            else -> ALL to raw
        }
    }
}
