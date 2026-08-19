# Models

No model files are bundled in this repository (§11). They are imported by the
user on-device via file pickers; the app copies them into app-private storage,
verifies **SHA-256**, reads GGUF metadata, and shows the model's **license before
download/import**.

## LLM — Phase 3 engine: LiteRT-LM (`.litertlm`)

The shipping Phase-3 engine is **LiteRT-LM** (Google AI Edge,
`com.google.ai.edge.litertlm:litertlm-android`) behind the `LlmEngine` interface
(`LitertLmEngine`). It runs fully offline on a `.litertlm` model the user imports;
the CPU backend is used for maximum device compatibility. This replaced the
MediaPipe LLM Inference API (`.task`), which Google put in maintenance mode and
which failed to open several current Gemma bundles ("Unable to open zip archive")
— frequently a corrupt/incomplete phone download. `.litertlm` models come from
verified sources: the **Google AI Edge Gallery** app or the **LiteRT community on
Hugging Face**. The `LlmEngine` seam keeps a llama.cpp/GGUF engine (below) a
drop-in alternative for later.

For the current phone setup, use **Gemma 3 1B Instruct** as the fast slot and a
mobile LiteRT-LM **Gemma 4 E4B** build as the advanced slot. "E4B" is the
official effective-parameter name; it is not a dense desktop 4B package. Both
files must be instruction-tuned, mobile-compatible `.litertlm` artifacts.

### Optional third brain: the classifier slot

`LlmRouter` also carries an optional third, independent `LitertLmEngine`
instance, used **only** by `LlmIntentClassifier` — the one-line-answer model
that decides which tool an utterance means once `CommandMatcher`'s
deterministic aliases don't recognise it. It never answers a real question and
never touches the conversation. When no classifier model is imported (the
default), classification silently falls back to the fast engine, so behaviour
is unchanged. When one *is* imported, it is completely isolated from ordinary
chat — it can be much smaller than the fast slot, since its only job is to
output one short line, e.g. a heavily quantized **Qwen 0.5B/0.8B** or similarly
tiny instruction-tuned `.litertlm` build. Manage it from Impostazioni ›
Modelli, "Classificatore" — same import/load/unload pattern as the other two
slots.

## LLM (llama.cpp, GGUF)

Profiles (§11):

| Profile | Model size | Context | Use |
|---------|-----------|---------|-----|
| **ECO** | 0.6B–1.7B quantized | 2048–4096 | Short replies, low power. |
| **BALANCED** *(default)* | 1.7B–4B quantized | 4096 | Everyday assistant. |
| **QUALITY** | Largest compatible | 4096+ | Best answers; warns on RAM/heat/battery. |

Suggested starting models (not hard-coded into the product): **Qwen3 1.7B** or
**Qwen3 4B** GGUF, quantized (e.g. Q4_K_M). The engine reads the chat template
from GGUF metadata; nothing is built around a single model.

### RAM-based recommendation (validate on device)

| Device RAM | Recommended | Quant | Notes |
|-----------|-------------|-------|-------|
| ~6 GB | 0.6B–1.7B | Q4_K_M | ECO; keep context ≤ 4096. |
| ~8 GB | 1.7B–3B | Q4_K_M | BALANCED default. |
| ~12 GB+ (HONOR 200 higher trims) | 3B–4B | Q4_K_M / Q5 | BALANCED/QUALITY; watch thermals. |

Before loading, the ModelManager checks: available RAM, free storage, ABI
(arm64-v8a), thermal status, battery, file size, and estimated context memory. If
the model is too large, loading is blocked with an explanation. Manual and
memory-pressure unload are supported. A benchmark screen reports load time,
tokens/sec, time-to-first-token, estimated RAM, context, temperature, and battery
used.

## STT + VAD (sherpa-onnx)

Use official, version-pinned sherpa-onnx releases. Requirements: Italian
recognition (multilingual model if it performs better), no network during
inference, configurable VAD and silence thresholds, cancellation, partial results
when supported, latency measurement, WAV fixtures for tests. Models are imported
explicitly with checksum verification; licenses shown first. `AndroidOnDeviceSpeechEngine`
(`SpeechRecognizer.createOnDeviceSpeechRecognizer()`) is a fallback **only when
the on-device service is available** — a cloud recognizer is never used silently.

## TTS (Supertonic 3, Kokoro, Piper)

See `docs/VOICE.md` for the full architecture, fallback chain and how to
update the voice. Summary: **Supertonic 3** (sherpa-onnx `1.13.5`,
`sherpa-onnx-supertonic-3-tts-int8-2026-05-11`, INT8) is the default engine —
the one model bundled in the APK (`app/src/main/assets/models/supertonic3/`,
`app/libs/sherpa-onnx-1.13.5.aar`) rather than user-imported, a deliberate
exception to this file's own §11 rule made for this engine specifically.
Kokoro and Piper remain available, still always user-imported, never bundled.
Falls back to the Android system voice automatically if Supertonic's model or
AAR is missing/corrupt/fails to initialise.

## Response protocol (§12)

The local system prompt lives at
`app/src/main/assets/prompts/jarvis_system_it.md` (editable in-app). The model is
asked to emit strict JSON:

```json
{
  "assistant_text": "Testo da pronunciare",
  "tool_calls": [],
  "memory_proposal": null,
  "follow_up_expected": false
}
```

A tool call:

```json
{ "id": "uuid", "name": "nome_tool", "arguments": {}, "requires_confirmation": true }
```

Validated with Kotlin Serialization (`core/…/protocol`). On invalid JSON: (1) run
no tools; (2) attempt exactly one controlled repair (strip fences/prose, extract
the outermost balanced object); (3) if it still fails, treat the text as a plain
spoken reply; (4) log only a technical error with no personal data. Grammar-
constrained generation is used when the runtime supports it stably.
