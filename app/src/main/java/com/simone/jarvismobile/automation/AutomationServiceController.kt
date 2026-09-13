package com.simone.jarvismobile.automation

import android.content.Context
import com.simone.jarvismobile.core.proactive.TriggerEvidenceSource
import com.simone.jarvismobile.core.proactive.TriggerEvidenceStage
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.proactive.TriggerEvidenceStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts or stops the [AutomationEventService] to match the user's preference.
 * Kept in one place so the Settings toggle, app start and (best-effort) boot all
 * go through the same decision instead of each poking the service directly.
 *
 * § JARVIS Implementation Master Plan — MICRO-PATCH 14.2.3 §3/§4/§11. Both
 * entry points now record a persistent, bounded diagnostic checkpoint —
 * "setting ON" is never equated with "service actually running": that is
 * exactly why [AutomationEventService] itself separately records reaching
 * `onCreate()`/`onStartCommand()`/registering its receiver.
 */
@Singleton
class AutomationServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val evidence: TriggerEvidenceStore,
) {
    /** Applies [enabled] immediately and lets the caller persist it separately. */
    suspend fun apply(enabled: Boolean) {
        if (enabled) {
            val started = AutomationEventService.start(context)
            evidence.record(
                TriggerEvidenceSource.FIRST_UNLOCK,
                if (started) TriggerEvidenceStage.SERVICE_START_REQUESTED else TriggerEvidenceStage.SERVICE_START_FAILED,
            )
        } else {
            AutomationEventService.stop(context)
            evidence.record(TriggerEvidenceSource.FIRST_UNLOCK, TriggerEvidenceStage.SERVICE_STOP_REQUESTED)
        }
    }

    /** Reads the saved preference and (re)starts or stops accordingly. */
    suspend fun syncFromSettings() {
        val enabled = settings.automationServiceEnabled.first()
        evidence.record(
            TriggerEvidenceSource.FIRST_UNLOCK,
            if (enabled) TriggerEvidenceStage.AUTOMATION_SETTING_ENABLED else TriggerEvidenceStage.AUTOMATION_SETTING_DISABLED,
        )
        apply(enabled)
    }
}
