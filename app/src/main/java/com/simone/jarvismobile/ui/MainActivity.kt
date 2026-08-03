package com.simone.jarvismobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.simone.jarvismobile.ui.theme.JarvisTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single Activity host. It requests the runtime permissions the audio loop needs
 * (RECORD_AUDIO, POST_NOTIFICATIONS, BLUETOOTH_CONNECT) with contextual UI, and
 * degrades gracefully — never crashing — when a permission is denied.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* results handled reactively by the screen state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val startListening = intent?.data?.let {
            it.scheme == "jarvis" && it.host == "listen"
        } ?: false

        requestNeededPermissions()

        setContent {
            JarvisTheme {
                JarvisApp(autoStartListening = startListening)
            }
        }
    }

    private fun requestNeededPermissions() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }
}
