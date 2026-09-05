package com.simone.jarvismobile.core.semantic

/**
 * § FASE 2A.9 §17 — a scripted [SemanticInterpreter] for tests that need to
 * drive [ConversationalJarvisEngine][com.simone.jarvismobile.engine.ConversationalJarvisEngine]'s
 * routing WITHOUT a real device/model (this project has no Robolectric/
 * instrumented infra — see `CLAUDE.md`'s "Environment note"). Lives in
 * `:core` test sources (not `app/`) so it stays reusable from any future
 * JVM-level test surface without depending on Android.
 *
 * Each call consumes ONE scripted response, in order, so a test can assert
 * "the second call receives THIS dialogue context" — never a static/shared
 * answer that would hide a caller passing the wrong previous frame.
 */
class FakeSemanticInterpreter(
    private val scripted: MutableList<SemanticInterpretation> = mutableListOf(),
) : SemanticInterpreter {

    val receivedContexts = mutableListOf<SemanticDialogueContext>()

    fun enqueue(interpretation: SemanticInterpretation) {
        scripted += interpretation
    }

    fun enqueueValid(frame: SemanticFrame) = enqueue(SemanticInterpretation.Valid(frame))

    override suspend fun interpret(text: String, dialogueContext: SemanticDialogueContext): SemanticInterpretation {
        receivedContexts += dialogueContext
        return scripted.removeFirstOrNull() ?: SemanticInterpretation.Invalid("no_scripted_response")
    }
}
