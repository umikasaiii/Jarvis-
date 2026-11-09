package com.simone.jarvismobile.core.reliability

enum class NetworkKind { NONE, METERED, UNMETERED }

enum class CloudUploadDecision { UPLOAD, DEFER_OFFLINE, DEFER_WIFI_ONLY, CLOUD_NOT_ENABLED }

/**
 * BETA 7 — the "Wi-Fi only" backup preference is about *uploading*, never about whether the local encrypted backup may
 * be made. The scheduler used to translate it into a WorkManager UNMETERED constraint on the whole job, so with
 * airplane mode or mobile data only (the offline-first case) the local backup never ran at all.
 *
 * The local snapshot always runs; this decides only what happens to the cloud copy. A deferred upload stays in the
 * persistent queue and is retried on a later run — it is never an error.
 */
object CloudUploadGate {
    fun decide(cloudEnabled: Boolean, wifiOnly: Boolean, network: NetworkKind): CloudUploadDecision = when {
        !cloudEnabled -> CloudUploadDecision.CLOUD_NOT_ENABLED
        network == NetworkKind.NONE -> CloudUploadDecision.DEFER_OFFLINE
        wifiOnly && network != NetworkKind.UNMETERED -> CloudUploadDecision.DEFER_WIFI_ONLY
        else -> CloudUploadDecision.UPLOAD
    }
}
