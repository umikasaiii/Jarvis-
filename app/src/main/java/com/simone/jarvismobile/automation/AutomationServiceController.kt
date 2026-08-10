package com.simone.jarvismobile.automation

import android.content.Context
import com.simone.jarvismobile.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts or stops the [AutomationEventService] to match the user's preference.
 * Kept in one place so the Settings toggle, app start and (best-effort) boot all
 * go through the same decision instead of each poking the service directly.
 */
@Singleton
class AutomationServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    /** Applies [enabled] immediately and lets the caller persist it separately. */
    fun apply(enabled: Boolean) {
        if (enabled) AutomationEventService.start(context) else AutomationEventService.stop(context)
    }

    /** Reads the saved preference and (re)starts or stops accordingly. */
    suspend fun syncFromSettings() {
        apply(settings.automationServiceEnabled.first())
    }
}
