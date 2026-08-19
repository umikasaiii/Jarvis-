package com.simone.jarvismobile.engine

import com.simone.jarvismobile.core.engine.JarvisEngineMode
import com.simone.jarvismobile.data.SettingsRepository
import com.simone.jarvismobile.llm.LlmLoadState
import com.simone.jarvismobile.llm.LlmRouter
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * The single switch between orchestrators — the exact one-`if`-at-the-top
 * pattern `ProModeManager`/`ProModeCoordinator` already use for NORMAL/PRO,
 * applied one axis higher. `SessionCoordinator` calls [route] once per turn
 * with both engines; this class only ever reads [SettingsRepository.jarvisEngineMode]
 * and picks one — it holds no conversation state of its own.
 *
 * Also owns the ONE fallback-to-Classic condition spec §11 asks for that this
 * class, not [ConversationalJarvisEngine], is positioned to check up front:
 * when Motore = Conversazionale but no local model is actually loaded, this
 * router calls [classic] instead — Classic already has an honest "no model
 * loaded" message and a fully deterministic `CommandMatcher` path that needs
 * no model at all, so failing over here gives strictly more capability than
 * letting the turn reach a brain that cannot answer. Every OTHER failure mode
 * (timeout, malformed output, the tool-call cap) is `ConversationalJarvisEngine`'s
 * own responsibility, resolved to a safe canned message rather than a
 * fallback — see that class.
 *
 * [JarvisEngineMode.IBRIDA] has no UI path to select it yet (see that enum's
 * doc comment); if it is ever persisted some other way, this router falls
 * back to Classico rather than calling an engine that does not exist.
 */
class JarvisEngineRouter @Inject constructor(
    private val settings: SettingsRepository,
    private val llmRouter: LlmRouter,
) {
    suspend fun route(
        transcript: String,
        classic: JarvisEngine,
        conversational: JarvisEngine,
    ): String = when (settings.jarvisEngineMode.first()) {
        JarvisEngineMode.CLASSICO -> classic.handle(transcript)
        JarvisEngineMode.CONVERSAZIONALE ->
            if (llmRouter.loadState.value == LlmLoadState.LOADED) {
                conversational.handle(transcript)
            } else {
                classic.handle(transcript)
            }
        JarvisEngineMode.IBRIDA -> classic.handle(transcript)
    }
}
