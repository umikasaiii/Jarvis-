package com.simone.jarvismobile.quality

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simone.jarvismobile.core.llm.ChatSeed
import com.simone.jarvismobile.core.llm.SeedTurn
import com.simone.jarvismobile.core.quality.ClassicPromptComposer
import com.simone.jarvismobile.core.quality.ContextBudget
import com.simone.jarvismobile.core.quality.PromptBudgeter
import com.simone.jarvismobile.core.quality.TokenEstimator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * HOTFIX 5 — on a real Android runtime (emulator): the system prompt asset that ships in the APK is present, compact,
 * and composes into a prompt that fits the context budget with a full-length history. NO model is loaded here:
 * this proves packaging and the budget arithmetic on-device, NOT answer quality.
 */
@RunWith(AndroidJUnit4::class)
class QualityAssetInstrumentedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun shippedPromptAssetIsCompactAndComposesWithinBudget() {
        val base = ctx.assets.open("prompts/jarvis_system_it.md").bufferedReader().use { it.readText() }
        assertTrue("asset too large: ${base.length}", base.length < 2_000)
        assertFalse("REGOLA FONDAMENTALE" in base)
        val budget = ContextBudget()
        val system = ClassicPromptComposer.compose(base, "Dai del tu, vai dritto al punto.", "JARVIS", LocalDateTime.of(2026, 11, 13, 10, 0), listOf("Il mio cibo preferito è la pizza."), "")
        val history = (1..20).flatMap { listOf(SeedTurn(true, "domanda $it"), SeedTurn(false, "risposta ".repeat(60))) }
        val plan = PromptBudgeter.plan(system, emptyList(), history, "Quanto è grande l'Asia?", budget)
        assertTrue("prompt ${plan.estTotal} exceeds ${budget.promptCeiling}", plan.estTotal <= budget.promptCeiling)
        assertTrue(TokenEstimator.estimate(system) < 700)
        assertTrue(ChatSeed(plan.history).normalized().isEmpty() || !ChatSeed(plan.history).normalized().last().fromUser)
    }
}
