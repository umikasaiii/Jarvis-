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
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
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
private val Silver = Color(0xFFD9DEE3)

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
                    provider = ImageProvider(R.drawable.jarvis_dragon),
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
        provideContent {
            // The 2x1 uses the supplied JARVIS pill artwork as its face (dragon in
            // the fire ring, name over the red divider, mic and chat buttons) and
            // overlays *real* functional hotspots so the mic and chat regions are
            // live controls, not a flat clickable picture. A dark scrim ground lets
            // the artwork sit undistorted (letterboxed) whatever the cell ratio.
            Box(
                modifier = GlanceModifier.fillMaxSize()
                    .background(ColorProvider(Ink.copy(alpha = prefs.transparency)))
                    .cornerRadius(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.jarvis_widget_2x1),
                    contentDescription = "JARVIS",
                    contentScale = ContentScale.Fit,
                    modifier = GlanceModifier.fillMaxSize(),
                )
                // Live status chip, shown only while JARVIS is actually busy, so the
                // artwork's own "Pronto" stands for the idle/ready state untouched.
                if (prefs.showStatus && label != READY_LABEL) {
                    Box(
                        modifier = GlanceModifier.fillMaxSize().padding(bottom = 6.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Text(
                            label,
                            style = TextStyle(color = ColorProvider(Silver), fontSize = 13.sp),
                            modifier = GlanceModifier
                                .background(ColorProvider(Color(0xE6120A0A)))
                                .cornerRadius(11.dp)
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                }
                // Functional hotspots over the artwork. Five equal columns: the left
                // four (dragon · name · status · mic) start a voice session, the far
                // right one (the chat bubble) opens the chat. Every cell carries its
                // own click so routing never depends on tap fall-through.
                Row(modifier = GlanceModifier.fillMaxSize()) {
                    Box(
                        GlanceModifier.defaultWeight().fillMaxHeight()
                            .clickable(actionStartActivity(JarvisIntents.voiceIntent(context))),
                    ) {}
                    Box(
                        GlanceModifier.defaultWeight().fillMaxHeight()
                            .clickable(actionStartActivity(JarvisIntents.voiceIntent(context))),
                    ) {}
                    Box(
                        GlanceModifier.defaultWeight().fillMaxHeight()
                            .clickable(actionStartActivity(JarvisIntents.voiceIntent(context))),
                    ) {}
                    Box(
                        GlanceModifier.defaultWeight().fillMaxHeight()
                            .clickable(actionStartActivity(JarvisIntents.voiceIntent(context))),
                    ) {}
                    Box(
                        GlanceModifier.defaultWeight().fillMaxHeight()
                            .clickable(actionStartActivity(JarvisIntents.chatIntent(context))),
                    ) {}
                }
            }
        }
    }

    private companion object {
        const val READY_LABEL = "Pronto"
    }
}

class JarvisControlWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = JarvisControlWidget()
}
