package com.simone.jarvismobile.core.engine

/**
 * Caps how many tool calls `ToolRouter` will execute within one conversational
 * turn (spec §11: "cap consecutive tool-calls to prevent infinite loops").
 * One instance per turn — `ConversationalJarvisEngine` creates a fresh one at
 * the start of [handle], so the count never leaks across turns.
 */
class ToolCallBudget(private val cap: Int) {
    init {
        require(cap >= 1) { "cap must be at least 1, was $cap" }
    }

    private var used = 0

    val usedCount: Int get() = used
    val exhausted: Boolean get() = used >= cap

    /** Returns true and consumes one slot if the budget allows it, else false. */
    fun tryConsume(): Boolean {
        if (used >= cap) return false
        used++
        return true
    }
}
