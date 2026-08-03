package com.simone.jarvismobile.tile

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.simone.jarvismobile.R
import com.simone.jarvismobile.ui.MainActivity

/**
 * Quick Settings tile (docs/ARCHITECTURE.md §7.2). Tapping it opens an EXPLICIT
 * listening session by launching the app via the `jarvis://listen` deep link — it
 * never starts a hidden recording and never starts a microphone foreground
 * service directly from the tile's background context (that path is restricted).
 * The Activity then starts the FGS while foregrounded and checks permissions.
 */
class JarvisTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            subtitle = getString(R.string.tile_subtitle_idle)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("jarvis://listen")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: the Intent overload is deprecated/throws; use PendingIntent.
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
