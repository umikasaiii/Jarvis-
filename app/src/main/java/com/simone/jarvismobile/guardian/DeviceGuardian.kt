package com.simone.jarvismobile.guardian

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.simone.jarvismobile.core.guardian.GuardianCheck
import com.simone.jarvismobile.core.guardian.GuardianEvidence
import com.simone.jarvismobile.core.guardian.GuardianPolicy
import com.simone.jarvismobile.core.guardian.GuardianState
import java.time.LocalDate

/**
 * G2: reads only public, user-permitted device signals. No network, no background monitor,
 * no app enumeration, no root, no accessibility and no side effects.
 */
class DeviceGuardian(private val context: Context) {
    fun snapshot(): List<GuardianCheck> {
        val lockSecure = runCatching {
            context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure
        }.getOrNull()
        val isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return listOf(
            GuardianCheck(
                "android_version", "Sistema Android", GuardianState.UNVERIFIED,
                "Versione riferita dal sistema: Android " + Build.VERSION.RELEASE +
                    ", API " + Build.VERSION.SDK_INT + ". La versione non dimostra da sola la sicurezza.",
                GuardianEvidence.OBSERVED,
            ),
            GuardianPolicy.securityPatch(Build.VERSION.SECURITY_PATCH, LocalDate.now()),
            GuardianPolicy.lockScreen(lockSecure),
            advancedProtection(),
            GuardianPolicy.betaSigning(isDebug),
            GuardianCheck(
                "verified_boot", "Bootloader e avvio verificato", GuardianState.UNAVAILABLE,
                "Una normale app non dispone qui di attestazione affidabile su bootloader e Verified Boot.",
                GuardianEvidence.INACCESSIBLE,
            ),
            GuardianCheck(
                "system_permissions", "Permessi delle altre app", GuardianState.UNAVAILABLE,
                "Android limita la lettura completa delle autorizzazioni delle altre app. Consulta le impostazioni di sistema.",
                GuardianEvidence.INACCESSIBLE,
            ),
        )
    }

    private fun advancedProtection(): GuardianCheck {
        if (Build.VERSION.SDK_INT < 36) return GuardianPolicy.advancedProtection(false, false, null)
        val allowed = context.checkSelfPermission(QUERY_ADVANCED_PROTECTION_MODE) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return GuardianPolicy.advancedProtection(true, false, null)
        val value = runCatching {
            // Compile SDK remains 35 for the existing Beta; the class exists at runtime only on API 36+.
            val cls = Class.forName("android.security.advancedprotection.AdvancedProtectionManager")
            val service = context.getSystemService(cls)
            if (service == null) null else cls.getMethod("isAdvancedProtectionEnabled").invoke(service) as? Boolean
        }.getOrNull()
        return GuardianPolicy.advancedProtection(true, true, value)
    }

    private companion object {
        const val QUERY_ADVANCED_PROTECTION_MODE = "android.permission.QUERY_ADVANCED_PROTECTION_MODE"
    }
}
