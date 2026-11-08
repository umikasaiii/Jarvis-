package com.simone.jarvismobile.core.llm

/** Mirror of the app's engine load state, so the wording below is testable without Android. */
enum class ModelRuntimeState { UNLOADED, LOADING, LOADED, ERROR }

/** Why the local model cannot answer right now. Distinct causes get distinct, actionable messages. */
enum class ModelUnavailableKind { NO_MODEL_IMPORTED, MODEL_FILE_MISSING, LOADING, LOAD_FAILED, NOT_LOADED }

sealed interface ModelAvailability {
    data object Ready : ModelAvailability
    data class Unavailable(val kind: ModelUnavailableKind, val message: String) : ModelAvailability
}

/**
 * BETA 2/8 — one place that turns "the engine is not ready" into an honest, specific sentence.
 * It never echoes the user's text and never pretends the model answered; a network problem is never
 * mentioned because the local pipeline does not use the network.
 */
object ModelAvailabilityResolver {

    fun resolve(
        state: ModelRuntimeState,
        configuredPath: String?,
        fileExists: Boolean,
        loadDetail: String = "",
    ): ModelAvailability {
        if (state == ModelRuntimeState.LOADED) return ModelAvailability.Ready
        fun no(kind: ModelUnavailableKind, msg: String) = ModelAvailability.Unavailable(kind, msg)
        return when {
            state == ModelRuntimeState.LOADING -> no(
                ModelUnavailableKind.LOADING,
                "Sto ancora caricando il modello in memoria: può servire qualche decina di secondi. Riprova tra poco.",
            )
            state == ModelRuntimeState.ERROR -> {
                val d = loadDetail.trim().take(140)
                no(
                    ModelUnavailableKind.LOAD_FAILED,
                    "Non sono riuscito a caricare il modello" + (if (d.isNotEmpty()) " ($d)" else "") +
                        ". Apri la schermata Modelli e riprova, oppure scegli un altro file .litertlm.",
                )
            }
            configuredPath.isNullOrBlank() -> no(
                ModelUnavailableKind.NO_MODEL_IMPORTED,
                "Su questo telefono non c'è ancora un modello AI. Importa un file .litertlm dalla schermata Modelli: " +
                    "dopo l'importazione funziono anche senza Internet.",
            )
            !fileExists -> no(
                ModelUnavailableKind.MODEL_FILE_MISSING,
                "Il file del modello non c'è più (spostato o eliminato). Importalo di nuovo dalla schermata Modelli.",
            )
            else -> no(
                ModelUnavailableKind.NOT_LOADED,
                "Il modello non è in memoria. Aprilo dalla schermata Modelli (tocca «Carica») e riprova.",
            )
        }
    }
}
