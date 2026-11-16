# M3 — Model profiles, token measurements, residency (evidence-labelled)

## LiteRT-LM API verified on the exact artifact
Artifact `com.google.ai.edge.litertlm:litertlm-android:0.14.0` (CI step "LiteRT-LM API inspection", `javap -public` of the AAR's `classes.jar`; the step now also prints the full public API of `Conversation`, `BenchmarkInfo`, `Engine`, `EngineConfig`, `ConversationConfig`, `SamplerConfig` for the record). Token-related members found (CI log of run 43899331699, filtered by `token|count`):
- `Conversation.getTokenCount(): int`
- `BenchmarkInfo.getLastPrefillTokenCount(): int`, `getLastDecodeTokenCount(): int`
- `EngineConfig.getMaxNumTokens(): Integer`
- `LiteRtLmJni.nativeConversationGetTokenCount(long)`
**No** public method pre-tokenises an arbitrary prompt. How a `BenchmarkInfo` instance is obtained was not visible in the filtered log; **it is not integrated** (no API invented). `Conversation.getTokenCount()` IS integrated (see below).

## Token measurements (M3-C) — what the figure is and is not
- Read in `InferenceSlot.generateOn` right after `conv.send()` returned, on the lane, before anything may close the conversation (never after close; a read that throws → no measurement).
- Reflection (`getMethod("getTokenCount")`) is used so a signature change degrades to "no measurement" instead of a crash. Semantics (total in context vs. last turn) are **undocumented**: recorded as `Source.CONVERSATION_COUNT` and never used to size the prompt.
- Carried by a per-request `TokenLedger` through the coroutine context (`TokenLedgerContext`), tied to request id, slot, epoch, call index and `configuredWindow`. There is no global "last value". Concurrent requests keep their own figures (JVM test, 12 parallel).
- `ContextEvent.measured` is true only when a runtime figure exists; `estimatedTokens` (ContextBudget/TokenEstimator) is unchanged and still drives ContextFit; the trace shows both side by side. At most two generations per turn (unchanged).
- Window: `EngineConfig.maxNumTokens = 4096` is the CONFIGURED window of every slot (`MAX_CONTEXT_TOKENS`), recorded per slot/profile; `ContextWindowCheck` compares it with the planned window without changing either. 4096 is not assumed universal; whether it is configurable per artifact was not verified (artifact-declared window is unknown → null).
- Scope: the classic chat turn only. Document/focus/stateless paths are not measured (PENDING).

## Model profiles (M3-A)
`core/llm/ModelProfile.kt` + `app/llm/ModelProfileStore.kt` (one atomic JSON file, no paths). Levels: UNTESTED → STATICALLY_COMPATIBLE (`.litertlm` extension, non-empty, not GGUF; weak) → RUNTIME_LOADED (`initialize()` returned) → INFERENCE_TESTED (a real answer after a load) → DEVICE_QUALIFIED (only via M4 protocol evidence: same device key, SHA-256, runtime version, backend, on top of INFERENCE_TESTED). A GGUF (even renamed) is detected by magic bytes and marked FAILED. A new runtime version or file voids qualification. Interrupted loads (marker never finished) are counted. SHA-256 is computed on demand only (streaming).
Models named in the brief (Gemma 4 E4B, Qwen 3.5 4B/9B): **no artifact, version or compatibility with LiteRT-LM was verified here → none is declared runnable**. If they are GGUF they need another runtime: see ADR note below.

## Residency (M3-B)
Existing, reused: serialised load (`loadMutex`), `ModelLoadGuard` journal (no automatic reload after a killed load), `ModelLoadAdvisor` (estimate = advice, never a barrier), `ModelResidencyPolicy` (park CLASSIFIER then ADVANCED only to make room when the estimate is INSUFFICIENT; FAST never parked), `InferenceSlot` lifecycle (no close while in use, deferred reset, wedge recovery). Scenario coverage:
| scenario | evidence |
|---|---|
| first load / same model again | `loadLocked` early return (CODE); profile test |
| FAST→ADVANCED→FAST | `M3ResidencyMatrixTest` (policy), `ConversationStateInstrumentedTest` (M1, real coordinator, seam) |
| classifier optional | residency matrix (JVM) |
| unload/switch during generation, Stop, timeout, concurrent callers, wedged slot, reset | `InferenceSlotTest` (JVM, fake engine) |
| initialize error | profile `onLoad` failure keeps the real error (JVM + emulator store test) |
| native crash/OOM (uncatchable) | journal marker → `M3RuntimeInstrumentedTest` (emulator, fake file) + `UnrestrictedLoadingTest` |
| unfavourable RAM estimate + consent | `UnrestrictedLoadingTest` (warning, never refusal) |
| cache/history isolated per slot | one `InferenceSlot` per engine (CODE) |
**Not measurable here / not measured:** thermal throttling, peak RAM, real initialize/unload durations on the Honor 200 (the load report records PSS before/after, available RAM and `initialize` duration when it runs on a device).

## ADR note — runtime alternatives (no migration performed)
A different engine would only be needed if a target model is GGUF-only (e.g. llama.cpp via JNI) — pros: broad model support; cons: second native runtime to maintain, different memory/thermal profile, new JNI attack surface, no integration with the existing `InferenceSlot` contract until adapted (`NativeEngine/NativeConversation` is already the narrow seam). Decision: **keep LiteRT-LM 0.14.0**; revisit only after M4 shows a needed model that has no `.litertlm` build. Nothing was installed.
