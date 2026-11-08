package com.simone.jarvismobile.core.chat

/**
 * BETA 2/8 — protection against a double tap or a duplicated notification action sending the same
 * message twice: an identical message that is still queued or being answered is not enqueued again.
 */
object EnqueueGuard {
    sealed interface Decision {
        data object Accept : Decision
        data class Duplicate(val existingTaskId: String) : Decision
        data object Empty : Decision
    }

    private fun norm(s: String) = s.trim().replace(Regex("""\s+"""), " ")

    /** [active] = (taskId, input) of tasks that are not yet COMPLETED/FAILED/CANCELLED. */
    fun decide(active: List<Pair<String, String>>, newInput: String): Decision {
        val n = norm(newInput)
        if (n.isEmpty()) return Decision.Empty
        active.firstOrNull { norm(it.second) == n }?.let { return Decision.Duplicate(it.first) }
        return Decision.Accept
    }

    /** What the user sees when a queued answer cannot be produced even after the automatic retry. */
    fun failureMessage(code: String?): String =
        "Non sono riuscito a completare la risposta" +
            (code?.takeIf { it.isNotBlank() }?.let { " (errore tecnico: ${it.take(60)})" } ?: "") +
            ". Puoi riprovare a scrivere il messaggio; se si ripete, scegli «Nuova conversazione» o ricarica il modello dalla schermata Modelli."
}
