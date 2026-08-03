package com.simone.jarvismobile.tile

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.simone.jarvismobile.ui.MainActivity

/**
 * Quick Settings tile (docs/ARCHITECTURE.md §7.2). Tapping it opens an EXPLICIT
 * listening session via the app; it never starts a hidden recording. The tile
 * reflects active/inactive state only.
 */
class JarvisTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // Launch the app straight into an explicit listening session. Using the
        // deep link keeps a single, auditable entry point (jarvis://listen).
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("jarvis://listen")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivityAndCollapse(pendingActivity(intent))
    }

    private fun pendingActivity(intent: Intent) =
        android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
