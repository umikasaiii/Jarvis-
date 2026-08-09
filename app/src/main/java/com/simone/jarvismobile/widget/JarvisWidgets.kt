package com.simone.jarvismobile.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.simone.jarvismobile.R
import com.simone.jarvismobile.llm.LlmLoadState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.simone.jarvismobile.audio.SessionCoordinator
import kotlinx.coroutines.flow.first

// --- shared palette (black / red / silver, per the reference) --------------
private val Ink = Color(0xFF0A0A0C)
private val Panel = Color(0xFF14100F)
private val Red = Color(0xFFE23A2E)
private val RedSoft = Color(0x33E23A2E)
private val Silver = Color(0xFFD9DEE3)
private val Muted = Color(0xFF8A9096)

/** Lets a widget read the live assistant state without duplicating any controller. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface JarvisWidgetEntryPoint {
    fun coordinator(): SessionCoordinator
    fun settings(): com.simone.jarvismobile.data.SettingsRepository
}

private fun readLabel(context: Context): String {
    val ep = EntryPointAccessors.fromApplication(context, JarvisWidgetEntryPoint::class.java)
    val coordinator = ep.coordinator()
    val ready = coordinator.llmLoadState.value == LlmLoadState.LOADED
    return JarvisWidgetState.label(coordinator.state.value, ready)
}

/** Widget appearance prefs, read once per redraw. */
private data class WidgetPrefs(val showStatus: Boolean, val transparency: Float)

private suspend fun readWidgetPrefs(context: Context): WidgetPrefs {
    val settings = EntryPointAccessors
        .fromApplication(context, JarvisWidgetEntryPoint::class.java).settings()
    return WidgetPrefs(
        showStatus = runCatching { settings.widgetShowStatus.first() }.getOrDefault(true),
        transparency = runCatching { settings.widgetTransparency.first() }.getOrDefault(1f),
    )
}

// --- Voice 1x1 --------------------------------------------------------------

class JarvisVoiceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Box(
                modifier = GlanceModifier.fillMaxSize()
                    .background(ColorProvider(Ink))
                    .cornerRadius(20.dp)
                    .clickable(actionStartActivity(JarvisIntents.voiceIntent(context))),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.orb),
                    contentDescription = "JARVIS voce",
                    modifier = GlanceModifier.size(56.dp),
                )
            }
        }
    }
}

class JarvisVoiceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = JarvisVoiceWidget()
}

// --- Chat 1x1 ---------------------------------------------------------------

class JarvisChatWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Box(
                modifier = GlanceModifier.fillMaxSize()
                    .background(ColorProvider(Ink))
                    .cornerRadius(20.dp)
                    .clickable(actionStartActivity(JarvisIntents.chatIntent(context))),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "💬",
                        style = TextStyle(fontSize = 26.sp),
                    )
                    Text(
                        "Chat",
                        style = TextStyle(color = ColorProvider(Silver), fontSize = 11.sp),
                    )
                }
            }
        }
    }
}

class JarvisChatWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = JarvisChatWidget()
}

// --- Control 2x1 ------------------------------------------------------------

class JarvisControlWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val label = readLabel(context)
        val prefs = readWidgetPrefs(context)
        val panel = Panel.copy(alpha = prefs.transparency)
        provideContent {
            Row(
                modifier = GlanceModifier.fillMaxSize()
                    .background(ColorProvider(panel))
                    .cornerRadius(24.dp)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.orb),
                    contentDescription = "JARVIS",
                    modifier = GlanceModifier.size(48.dp)
                        .clickable(actionStartActivity(JarvisIntents.voiceIntent(context))),
                )
                Spacer(GlanceModifier.width(12.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        "JARVIS",
                        style = TextStyle(
                            color = ColorProvider(Silver),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    if (prefs.showStatus) {
                        Text(
                            label,
                            style = TextStyle(color = ColorProvider(Red), fontSize = 13.sp),
                        )
                    }
                }
                // Microphone → voice.
                Box(
                    modifier = GlanceModifier.size(44.dp)
                        .background(ColorProvider(RedSoft))
                        .cornerRadius(22.dp)
                        .clickable(actionStartActivity(JarvisIntents.voiceIntent(context))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🎤", style = TextStyle(fontSize = 20.sp))
                }
                Spacer(GlanceModifier.width(8.dp))
                // Chat → ChatScreen.
                Box(
                    modifier = GlanceModifier.size(44.dp)
                        .background(ColorProvider(RedSoft))
                        .cornerRadius(22.dp)
                        .clickable(actionStartActivity(JarvisIntents.chatIntent(context))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("💬", style = TextStyle(fontSize = 20.sp))
                }
                Spacer(GlanceModifier.width(8.dp))
                // Stop → cancel (broadcast; never opens the app).
                Box(
                    modifier = GlanceModifier.size(30.dp)
                        .clickable(actionSendBroadcast(JarvisIntents.stopBroadcast(context))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⏹", style = TextStyle(color = ColorProvider(Muted), fontSize = 16.sp))
                }
            }
        }
    }
}

class JarvisControlWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = JarvisControlWidget()
}
