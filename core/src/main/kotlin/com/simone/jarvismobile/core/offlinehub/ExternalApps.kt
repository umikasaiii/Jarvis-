package com.simone.jarvismobile.core.offlinehub

/**
 * The external offline apps JARVIS hands work to (BETA 6). Package ids come from the projects' own
 * sources: Kiwix `namespace org.kiwix.kiwixmobile` + the `standalone` build type's `.standalone`
 * suffix (kiwix-android `app/build.gradle.kts`); Organic Maps `appId = 'app.organicmaps'` and the
 * `web` flavor's `.web` suffix (organicmaps `android/app/build.gradle`).
 */
enum class ExternalApp(val label: String, val packages: List<String>) {
    KIWIX("Kiwix", listOf("org.kiwix.kiwixmobile", "org.kiwix.kiwixmobile.standalone")),
    ORGANIC_MAPS("Organic Maps", listOf("app.organicmaps", "app.organicmaps.web")),
}

/** Which installed package to use, given what is on the phone and what the user prefers. */
sealed interface AppDetection {
    data object NotInstalled : AppDetection
    data class Single(val packageId: String) : AppDetection
    /** More than one variant installed and no valid preference: the user must choose. */
    data class Choose(val packages: List<String>) : AppDetection
}

object ExternalAppDetector {
    /** [installed] = the candidate package ids that are visible AND installed. */
    fun detect(app: ExternalApp, installed: Set<String>, preferred: String? = null): AppDetection {
        val present = app.packages.filter { it in installed }
        return when {
            present.isEmpty() -> AppDetection.NotInstalled
            present.size == 1 -> AppDetection.Single(present.single())
            preferred != null && preferred in present -> AppDetection.Single(preferred)
            else -> AppDetection.Choose(present)
        }
    }

    fun variantLabel(packageId: String): String = when (packageId) {
        "org.kiwix.kiwixmobile" -> "Kiwix (Play Store / F-Droid)"
        "org.kiwix.kiwixmobile.standalone" -> "Kiwix (standalone)"
        "app.organicmaps" -> "Organic Maps"
        "app.organicmaps.web" -> "Organic Maps (web)"
        else -> packageId
    }
}

/** What happened when JARVIS tried to hand over to an external app. */
sealed interface LaunchOutcome {
    /** The system accepted the intent. It does NOT prove the app then did what we asked. */
    data class Launched(val packageId: String?, val note: String = "") : LaunchOutcome
    data class NotInstalled(val app: ExternalApp) : LaunchOutcome
    /** The app is installed but no activity handles this intent (older version, or not supported). */
    data class NotHandled(val app: ExternalApp, val what: String) : LaunchOutcome
    data class NeedsChoice(val app: ExternalApp, val packages: List<String>) : LaunchOutcome
    data class Failed(val code: String) : LaunchOutcome
    data class Invalid(val reason: String) : LaunchOutcome
}

object LaunchMessages {
    fun text(o: LaunchOutcome): String = when (o) {
        is LaunchOutcome.Launched -> o.note.ifBlank { "Fatto." }
        is LaunchOutcome.NotInstalled -> "${o.app.label} non è installato. Installalo da Play Store o F-Droid " +
            "(JARVIS non installa app da solo) e scarica prima i contenuti, poi funziona anche senza Internet."
        is LaunchOutcome.NotHandled -> "${o.app.label} è installato ma questa versione non gestisce ${o.what}. " +
            "Aggiornala oppure usa l'app direttamente."
        is LaunchOutcome.NeedsChoice -> "Ho trovato più versioni di ${o.app.label}: " +
            o.packages.joinToString(", ") { ExternalAppDetector.variantLabel(it) } + ". Scegline una nella schermata Biblioteca/Mappe."
        is LaunchOutcome.Failed -> "Non sono riuscito ad aprire l'app (${o.code})."
        is LaunchOutcome.Invalid -> o.reason
    }
}
