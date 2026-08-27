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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.simone.jarvismobile.R
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import com.simone.jarvismobile.audio.SessionCoordinator
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.ui.theme.JarvisThemeId
import com.simone.jarvismobile.ui.theme.JarvisTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
    @Inject lateinit var settings: SettingsRepository

    private val openChatRequests = MutableStateFlow(0)
    private val startListeningRequests = MutableStateFlow(0)
    private val openAgendaRequests = MutableStateFlow(0)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* results handled reactively by the screen state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Stop is an action, not a screen: honour it even on a cold start.
        if (isStopRequest(intent)) coordinator.cancel()
        val startListening = isListeningRequest(intent)
        val openChat = startListening || isChatRequest(intent) ||
            intent?.getBooleanExtra(EXTRA_OPEN_CHAT, false) == true
        if (openChat) openChatRequests.value += 1
        if (startListening) startListeningRequests.value += 1
        if (isAgendaRequest(intent)) openAgendaRequests.value += 1

        requestNeededPermissions()

        setContent {
            val openChatRequest by openChatRequests.collectAsState()
            val startListeningRequest by startListeningRequests.collectAsState()
            val openAgendaRequest by openAgendaRequests.collectAsState()
            val themeId by remember { settings.themeId.map { JarvisThemeId.from(it) } }
                .collectAsState(initial = JarvisThemeId.BLU)
            JarvisTheme(themeId = themeId) {
                Box(Modifier.fillMaxSize()) {
                    JarvisApp(
                        initiallyOpenChat = openChat,
                        openChatRequest = openChatRequest,
                        startListeningRequest = startListeningRequest,
                        openAgendaRequest = openAgendaRequest,
                    )
                    // Brief full-screen opening artwork, then a quick fade to the app.
                    // Purely a transition: a short, fixed window, never a load gate.
                    var showSplash by remember { mutableStateOf(true) }
                    LaunchedEffect(Unit) {
                        delay(SPLASH_MS)
                        showSplash = false
                    }
                    AnimatedVisibility(visible = showSplash, exit = fadeOut(tween(320))) {
                        Image(
                            painter = painterResource(R.drawable.splash_full),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isStopRequest(intent)) coordinator.cancel()
        val startListening = isListeningRequest(intent)
        if (startListening || isChatRequest(intent) || intent.getBooleanExtra(EXTRA_OPEN_CHAT, false)) {
            openChatRequests.value += 1
        }
        if (startListening) startListeningRequests.value += 1
        if (isAgendaRequest(intent)) openAgendaRequests.value += 1
    }

    /** Agenda: the reminder notification's tap target. */
    private fun isAgendaRequest(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_OPEN_AGENDA, false) == true || jarvisHost(intent) == "agenda"

    override fun onResume() {
        super.onResume()
        // Re-read Obsidian after returning from an editor. The index throttles
        // rapid resumes, and the Memory screen still offers an immediate sync.
        lifecycleScope.launch { coordinator.ensureMemoryReady() }
    }

    /** Voice: system Assist, or jarvis://voice / jarvis://listen. */
    private fun isListeningRequest(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_ASSIST || jarvisHost(intent) in setOf("listen", "voice")

    /** Chat: jarvis://chat. */
    private fun isChatRequest(intent: Intent?): Boolean = jarvisHost(intent) == "chat"

    /** Stop: jarvis://stop — cancels LLM generation, TTS and any voice session. */
    private fun isStopRequest(intent: Intent?): Boolean = jarvisHost(intent) == "stop"

    private fun jarvisHost(intent: Intent?): String? =
        intent?.data?.takeIf { it.scheme == "jarvis" }?.host

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
        const val EXTRA_OPEN_AGENDA = "open_agenda"
        const val EXTRA_AGENDA_ENTRY_ID = "agenda_entry_id"
        /** How long the opening artwork stays before fading — a transition, not a wait. */
        private const val SPLASH_MS = 650L

        /**
         * Pure mirror of the deep-link host rule (a `jarvis://<host>` URI yields
         * `<host>`, anything else null), extracted so the contract with
         * [com.simone.jarvismobile.widget.JarvisIntents] is unit-testable without
         * an Android [android.net.Uri].
         */
        fun jarvisHostForTest(scheme: String?, host: String?): String? =
            if (scheme == "jarvis") host else null
    }
}
