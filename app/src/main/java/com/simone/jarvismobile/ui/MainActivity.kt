package com.simone.jarvismobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.ui.theme.JarvisTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single Activity host. It requests the runtime permissions the audio loop needs
 * (RECORD_AUDIO, POST_NOTIFICATIONS, BLUETOOTH_CONNECT) with contextual UI, and
 * degrades gracefully — never crashing — when a permission is denied.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var coordinator: SessionCoordinator

    private val openChatRequests = MutableStateFlow(0)
    private val startListeningRequests = MutableStateFlow(0)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* results handled reactively by the screen state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val startListening = isListeningRequest(intent)
        val openChat = startListening || intent?.getBooleanExtra(EXTRA_OPEN_CHAT, false) == true
        if (openChat) openChatRequests.value += 1
        if (startListening) startListeningRequests.value += 1

        requestNeededPermissions()

        setContent {
            val openChatRequest by openChatRequests.collectAsState()
            val startListeningRequest by startListeningRequests.collectAsState()
            JarvisTheme {
                JarvisApp(
                    initiallyOpenChat = openChat,
                    openChatRequest = openChatRequest,
                    startListeningRequest = startListeningRequest,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val startListening = isListeningRequest(intent)
        if (startListening || intent.getBooleanExtra(EXTRA_OPEN_CHAT, false)) {
            openChatRequests.value += 1
        }
        if (startListening) startListeningRequests.value += 1
    }

    override fun onResume() {
        super.onResume()
        // Re-read Obsidian after returning from an editor. The index throttles
        // rapid resumes, and the Memory screen still offers an immediate sync.
        lifecycleScope.launch { coordinator.ensureMemoryReady() }
    }

    private fun isListeningRequest(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_ASSIST || intent?.data?.let {
            it.scheme == "jarvis" && it.host == "listen"
        } == true

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

    companion object {
        const val EXTRA_OPEN_CHAT = "open_chat"
    }
}
