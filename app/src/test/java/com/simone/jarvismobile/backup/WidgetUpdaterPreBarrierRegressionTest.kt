package com.simone.jarvismobile.backup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * § JARVIS Implementation Master Plan PASSAGGIO 10.2 §3 — `JarvisApplication.
 * onCreate()` starts `widgetUpdater.start()` BEFORE the restore-recovery
 * barrier. That is only safe because `JarvisWidgetUpdater` observes
 * `SessionCoordinator.state` — a plain in-memory `ConversationStateMachine`,
 * never Room/DataStore-backed canonical state — confirmed by reading its
 * real source at PASSAGGIO 10.2 audit time.
 *
 * Rather than trust that finding to stay true forever, this test scans the
 * real `app/src/main` source of [com.simone.jarvismobile.widget.JarvisWidgetUpdater]
 * at test time for any reference to the restored-state surfaces it must
 * never touch (Room's `JarvisDatabase`/any `*Dao`, `SettingsRepository`/
 * DataStore). If a future change makes it depend on any of those, this
 * fails and forces re-auditing whether it still belongs before the barrier
 * — a plain file-content check, no Android/Robolectric needed.
 */
class WidgetUpdaterPreBarrierRegressionTest {

    @Test
    fun `JarvisWidgetUpdater never references Room or DataStore canonical state`() {
        val file = widgetUpdaterSource()
        val text = file.readText()

        val forbidden = listOf(
            "JarvisDatabase",
            "SettingsRepository",
            "DataStore",
            "Dao",
            "AgendaRepository",
            "MemoryIndex",
            "AutomationRepository",
        )
        val hits = forbidden.filter { text.contains(it) }

        assertTrue(
            "JarvisWidgetUpdater now references restored-state surface(s) $hits — " +
                "it is started BEFORE JarvisApplication's restore-recovery barrier " +
                "(§ PASSAGGIO 10.2 §3) precisely because it was proven independent " +
                "of Room/DataStore canonical state; re-audit whether it must move " +
                "behind the barrier before removing this failure.",
            hits.isEmpty(),
        )
    }

    private fun widgetUpdaterSource(): File {
        val candidates = listOf(
            File("src/main/java/com/simone/jarvismobile/widget/JarvisWidgetUpdater.kt"),
            File("app/src/main/java/com/simone/jarvismobile/widget/JarvisWidgetUpdater.kt"),
        )
        val found = candidates.firstOrNull { it.isFile }
        checkNotNull(found) {
            "Could not locate JarvisWidgetUpdater.kt from working directory " +
                "${File(".").absolutePath} — this test cannot silently pass without reading the real file."
        }
        return found
    }
}
