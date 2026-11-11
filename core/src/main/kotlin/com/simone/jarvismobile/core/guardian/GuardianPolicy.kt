package com.simone.jarvismobile.core.guardian

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** G2: a status represents ONE observed control, never the security of the whole phone. */
enum class GuardianState { PROTECTED, ATTENTION, UNVERIFIED, UNAVAILABLE }
enum class GuardianEvidence { APPLIED, OBSERVED, USER_ACTION, INACCESSIBLE }

data class GuardianCheck(
    val id: String,
    val title: String,
    val state: GuardianState,
    val details: String,
    val evidence: GuardianEvidence,
)

/** Pure, deterministic assessment: Android APIs are accessed only by the platform adapter. */
object GuardianPolicy {
    const val MAX_FILE_BYTES: Long = 128L * 1024L * 1024L
    private const val PATCH_MAX_AGE_DAYS: Long = 90L

    fun lockScreen(secure: Boolean?): GuardianCheck = when (secure) {
        true -> GuardianCheck(
            "secure_lock", "Blocco schermo", GuardianState.PROTECTED,
            "Android riferisce che è configurata una credenziale sicura. Non prova che il telefono sia invulnerabile.",
            GuardianEvidence.OBSERVED,
        )
        false -> GuardianCheck(
            "secure_lock", "Blocco schermo", GuardianState.ATTENTION,
            "Android non rileva una credenziale sicura. Configura PIN, password o altro blocco nelle impostazioni.",
            GuardianEvidence.USER_ACTION,
        )
        null -> GuardianCheck(
            "secure_lock", "Blocco schermo", GuardianState.UNVERIFIED,
            "Stato non leggibile tramite le API disponibili.", GuardianEvidence.INACCESSIBLE,
        )
    }

    fun securityPatch(raw: String?, today: LocalDate): GuardianCheck {
        val date = runCatching { LocalDate.parse(raw?.trim().orEmpty()) }.getOrNull()
            ?: return GuardianCheck(
                "patch", "Patch di sicurezza", GuardianState.UNVERIFIED,
                "La data della patch non è stata fornita in formato valido dal sistema.",
                GuardianEvidence.INACCESSIBLE,
            )
        val age = ChronoUnit.DAYS.between(date, today)
        return when {
            age < 0 -> GuardianCheck(
                "patch", "Patch di sicurezza", GuardianState.UNVERIFIED,
                "La patch riportata da Android ha una data futura: " + date + ". Controlla data e ora.",
                GuardianEvidence.OBSERVED,
            )
            age > PATCH_MAX_AGE_DAYS -> GuardianCheck(
                "patch", "Patch di sicurezza", GuardianState.ATTENTION,
                "Data riportata da Android: " + date + " (" + age + " giorni fa). Verifica gli aggiornamenti nelle impostazioni; JARVIS non può rilevare le OTA disponibili.",
                GuardianEvidence.USER_ACTION,
            )
            else -> GuardianCheck(
                "patch", "Patch di sicurezza", GuardianState.PROTECTED,
                "Data riportata da Android: " + date + " (" + age + " giorni fa). Controllo di freschezza, non una scansione vulnerabilità o OTA.",
                GuardianEvidence.OBSERVED,
            )
        }
    }

    fun advancedProtection(supported: Boolean, permission: Boolean, enabled: Boolean?): GuardianCheck = when {
        !supported -> GuardianCheck(
            "advanced_protection", "Android Advanced Protection", GuardianState.UNAVAILABLE,
            "Questa API è disponibile soltanto da Android 16 (API 36).", GuardianEvidence.INACCESSIBLE,
        )
        !permission -> GuardianCheck(
            "advanced_protection", "Android Advanced Protection", GuardianState.UNVERIFIED,
            "Il permesso di sola lettura per interrogare Advanced Protection non risulta concesso.",
            GuardianEvidence.INACCESSIBLE,
        )
        enabled == null -> GuardianCheck(
            "advanced_protection", "Android Advanced Protection", GuardianState.UNVERIFIED,
            "La risposta del servizio Android non è disponibile. Non assumo che la protezione sia attiva.",
            GuardianEvidence.INACCESSIBLE,
        )
        enabled -> GuardianCheck(
            "advanced_protection", "Android Advanced Protection", GuardianState.PROTECTED,
            "Android conferma che Advanced Protection è attiva. JARVIS non modifica le restrizioni di sistema.",
            GuardianEvidence.OBSERVED,
        )
        else -> GuardianCheck(
            "advanced_protection", "Android Advanced Protection", GuardianState.ATTENTION,
            "Android riferisce che Advanced Protection è disattivata. Puoi valutarne l'attivazione nelle impostazioni.",
            GuardianEvidence.USER_ACTION,
        )
    }

    fun betaSigning(debuggable: Boolean): GuardianCheck = if (debuggable) GuardianCheck(
        "app_build", "Firma e modalità app", GuardianState.ATTENTION,
        "Questa installazione JARVIS è una build debug. Non è una release di produzione verificata.",
        GuardianEvidence.OBSERVED,
    ) else GuardianCheck(
        "app_build", "Firma e modalità app", GuardianState.UNVERIFIED,
        "Build non debuggable: la provenienza e l'autenticità della release richiedono una verifica separata.",
        GuardianEvidence.INACCESSIBLE,
    )

    fun fileSizeAllowed(length: Long): Boolean = length in 0..MAX_FILE_BYTES
}
