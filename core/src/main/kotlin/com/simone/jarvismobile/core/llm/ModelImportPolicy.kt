package com.simone.jarvismobile.core.llm

sealed interface ImportVerdict {
    data class Accept(val safeName: String) : ImportVerdict
    data class Reject(val reason: Reason, val message: String) : ImportVerdict
    enum class Reason { UNSUPPORTED_FORMAT, TOO_SMALL, NOT_ENOUGH_SPACE, IN_USE }
}

/**
 * BETA 2/8 — checks made BEFORE a multi-gigabyte copy starts, so an incompatible or impossible import
 * fails fast and leaves the current model untouched.
 */
object ModelImportPolicy {
    const val REQUIRED_EXTENSION = ".litertlm"
    const val MIN_MODEL_BYTES = 50L * 1024 * 1024
    private const val SPACE_MARGIN_BYTES = 200L * 1024 * 1024

    /** Display names come from a provider: never let them carry a path or reserved characters. */
    fun safeFileName(raw: String?, fallback: String): String {
        val cleaned = (raw ?: "").replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trim('.')
        return cleaned.ifBlank { fallback }.take(120)
    }

    fun validate(
        displayName: String?,
        sizeBytes: Long,
        freeBytes: Long,
        inUse: Boolean,
        fallbackName: String,
    ): ImportVerdict {
        val name = safeFileName(displayName, fallbackName)
        if (!name.lowercase().endsWith(REQUIRED_EXTENSION)) {
            return ImportVerdict.Reject(
                ImportVerdict.Reason.UNSUPPORTED_FORMAT,
                "Formato non supportato: JARVIS carica modelli LiteRT-LM con estensione $REQUIRED_EXTENSION " +
                    "(hai scelto «$name»). Nessun file è stato copiato.",
            )
        }
        if (sizeBytes in 1 until MIN_MODEL_BYTES) {
            return ImportVerdict.Reject(
                ImportVerdict.Reason.TOO_SMALL,
                "Il file è troppo piccolo per essere un modello (${sizeBytes / 1024} KiB): probabilmente è incompleto o è un file diverso.",
            )
        }
        if (inUse) {
            return ImportVerdict.Reject(
                ImportVerdict.Reason.IN_USE,
                "«$name» è il modello attualmente in uso: scaricalo dalla memoria (Modelli › Scarica) prima di reimportarlo. " +
                    "Il file attuale non è stato toccato.",
            )
        }
        if (sizeBytes > 0 && freeBytes in 0 until (sizeBytes + SPACE_MARGIN_BYTES)) {
            val need = (sizeBytes + SPACE_MARGIN_BYTES) / (1024 * 1024)
            return ImportVerdict.Reject(
                ImportVerdict.Reason.NOT_ENOUGH_SPACE,
                "Spazio insufficiente per importare il modello: servono circa $need MiB liberi, ce ne sono ${freeBytes / (1024 * 1024)}. " +
                    "Libera spazio e riprova.",
            )
        }
        return ImportVerdict.Accept(name)
    }
}
