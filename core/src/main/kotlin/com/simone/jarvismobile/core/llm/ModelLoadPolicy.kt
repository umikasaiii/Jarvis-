package com.simone.jarvismobile.core.llm

/** What the caller must do before trying to load a model. There is deliberately NO "refuse" outcome. */
sealed interface LoadDecision {
    /** Go ahead; show [warning] (a tight or, in experimental mode, insufficient estimate) as information. */
    data class Proceed(val warning: String?) : LoadDecision

    /** The default, prudent mode and a clearly insufficient estimate: ask once, "Carica comunque" proceeds, "Annulla" does not. */
    data class NeedsConfirmation(val message: String) : LoadDecision
}

/**
 * Turns the memory estimate into a decision. The user always has a way to attempt the load:
 *  - default mode: an insufficient estimate asks for confirmation (every time, because the default is the cautious one);
 *  - "Consenti caricamento sperimentale di modelli pesanti": the attempt is made without asking again;
 *  - a confirmation given for this attempt counts for this attempt only.
 * File validity, format and runtime compatibility are NOT part of this policy and are never relaxed by it.
 */
object ModelLoadPolicy {
    fun decide(advice: LoadAdvice, experimentalHeavy: Boolean, userConfirmed: Boolean): LoadDecision = when (advice.level) {
        LoadAdviceLevel.OK -> LoadDecision.Proceed(null)
        LoadAdviceLevel.TIGHT -> LoadDecision.Proceed(advice.message)
        LoadAdviceLevel.INSUFFICIENT ->
            if (experimentalHeavy || userConfirmed) LoadDecision.Proceed(advice.message)
            else LoadDecision.NeedsConfirmation(advice.message.orEmpty())
    }

    /**
     * Loading WITHOUT the user in front of the screen (app start). Stricter than a manual attempt: an insufficient estimate is
     * loaded automatically only when the user enabled the experimental mode, and never for a model whose previous attempt was
     * interrupted ([LoadGuard]).
     */
    fun autoLoadAllowed(advice: LoadAdvice, experimentalHeavy: Boolean, interruptedBefore: Boolean): Boolean = when {
        interruptedBefore -> false
        advice.level == LoadAdviceLevel.INSUFFICIENT -> experimentalHeavy
        else -> true
    }
}
