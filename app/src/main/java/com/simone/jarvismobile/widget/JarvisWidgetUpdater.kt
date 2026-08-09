package com.simone.jarvismobile.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.simone.jarvismobile.audio.SessionCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the 2×1 control widget's status in sync with the assistant. It observes
 * the existing [SessionCoordinator] state — no new source of truth — and asks
 * Glance to redraw whenever the label would change ("Pronto" → "Ascolto…" → …).
 */
@Singleton
class JarvisWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coordinator: SessionCoordinator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch {
            coordinator.state
                .map { it::class }        // redraw on state change, not on every emission
                .distinctUntilChanged()
                .collect {
                    runCatching { JarvisControlWidget().updateAll(context) }
                }
        }
    }
}
