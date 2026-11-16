package com.simone.jarvismobile.net

import com.simone.jarvismobile.core.net.OfflineMode
import kotlinx.coroutines.flow.first

/**
 * Test helper: remembers the offline mode and the Core PC settings that are ACTUALLY persisted on the installation under test and puts them back,
 * whatever a test did (also after a failed assertion, when it is called from `@After`). The gate is closed (STRICT_OFFLINE) while the Core settings
 * are written back, so restoring cannot make the app's own background probe reach anything; only the last step re-applies the original mode.
 * A STRICT_OFFLINE installation is therefore STRICT_OFFLINE again afterwards, and EXPLICIT_ONLINE one is EXPLICIT_ONLINE.
 */
internal class OfflinePrefsSnapshot(private val entry: OfflineEntryPoint) {
    var mode: OfflineMode = OfflineMode.EXPLICIT_ONLINE; private set
    var host: String = ""; private set
    var port: Int = 0; private set
    var coreEnabled: Boolean = false; private set
    private var captured = false

    suspend fun capture() {
        val s = entry.settings()
        mode = s.offlineMode.first(); host = s.coreHost.first(); port = s.corePort.first(); coreEnabled = s.coreEnabled.first()
        captured = true
    }

    suspend fun restore() {
        if (!captured) return // nothing was changed by this test run: leave the installation exactly as found
        val s = entry.settings()
        s.setOfflineMode(OfflineMode.STRICT_OFFLINE)
        s.setCoreEnabled(false)
        s.setCoreHost(host); s.setCorePort(port)
        s.setCoreEnabled(coreEnabled)
        s.setOfflineMode(mode) // last: the original mode (and the in-process gate) is back only when everything else already is
    }
}
