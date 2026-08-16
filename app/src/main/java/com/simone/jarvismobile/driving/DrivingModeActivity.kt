package com.simone.jarvismobile.driving

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.simone.jarvismobile.ui.driving.DrivingModeScreen
import dagger.hilt.android.AndroidEntryPoint

/**
 * `INTERNAL_JARVIS_NAVIGATION`'s host — a real, standalone JARVIS Activity,
 * never a `WindowManager` overlay (spec §2/§16). Reachable only through the
 * developer feature flag (Diagnostica › "Modalità Guida (sviluppo)") while
 * this UI is being built; [DrivingModeService]/[DrivingModeManager] and the
 * `EXTERNAL_MAPS_OVERLAY` path are completely untouched by this class.
 *
 * Uses a plain dark Material3 theme, not [com.simone.jarvismobile.ui.theme.JarvisTheme]
 * (the app's cyan HUD) — same reasoning as [com.simone.jarvismobile.ui.driving.DrivingSportColors]:
 * this is a distinct, dedicated surface with its own palette.
 */
@AndroidEntryPoint
class DrivingModeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DrivingModeScreen(onClose = { finish() })
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, DrivingModeActivity::class.java)
    }
}
