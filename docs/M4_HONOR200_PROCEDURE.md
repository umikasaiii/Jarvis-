# M4.4 — Honor 200 qualification procedure (to be executed by the user; no result here is real)

Reuses `docs/M3_HONOR200_PROTOCOL.md` (model matrix, offline checks). Rules: never two large models at once; no crash/OOM is forced; your personal notes, memory, documents and archives are not touched; the isolated Beta (`...debug.beta`) is used.

## A. Preparation
1. Install `JARVIS_BETA.apk`; open Impostazioni and note the build (commit/SHA are in the release notes). Confirm the main JARVIS app is still installed/unchanged (the Beta has its own application id).
2. Import ONE `.litertlm` model compatible with LiteRT-LM 0.14.0 (Modelli ▸ Importa). A GGUF cannot work. Select it as FAST and load it. Write down: file name, size, **SHA-256** (Modelli ▸ profilo ▸ calcola hash), load time, memory before/after shown in the load report.
3. Battery % and temperature at start; phone unplugged, normal brightness, Wi-Fi ON.

## B. Manual checks (record Pass/Fail + a note; times with a stopwatch)
| # | Step | Pass criterion |
|---|---|---|
| 1 | 5 simple questions ("ciao", "che ore sono") | answers, no errors |
| 2 | 10 factual questions from `bench_v1_cases.json` (geografia/scienze) with the fixture archive NOT installed | answers carry the "non verificato" qualifier (no source) — not a bare claim |
| 3 | Italian ZIM (your own, verified in Impostazioni ▸ Conoscenza): 15 questions answered in the lead, 10 in an internal section, 5 absent, 5 with typos | record per question: answered / cited section / abstained |
| 4 | For each answered question check the footer: right article AND right section | a real source that does not support the claim = FAIL |
| 5 | Ask 5 questions with no source at all | explicit "non è verificato" framing, no invented citation |
| 6 | Stop during a long answer, then ask again | no late text, next turn works |
| 7 | 20 consecutive turns (mix topics, one correction, one "nuova conversazione") | no crash; context kept until the reset |
| 8 | FAST/ADVANCED switch — only if you really have both models | answers after each switch |
| 9 | Impostazioni ▸ Core: **Solo offline ON**; repeat 5 questions; Diagnostica: the trace shows no `ALLOW` network event | none |
| 10 | Airplane mode ON, STRICT_OFFLINE ON: repeat 5 questions | same behaviour |
| 11 | Move the ZIM file away (archive unreadable), ask 3 questions, put it back | the answer says the search was partial; an absent topic is NOT declared absent |
| 12 | Close the app mid-answer, reopen | no data lost; history intact |
| 13 | After 10 minutes of use: battery %, temperature, any lag | write them down |
Do **not** force a crash or OOM. Restore your offline-mode preference afterwards.

## C. Automated run (optional, needs a computer with adb)
`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.simone.jarvismobile.bench.M4DeviceRunInstrumentedTest -Pandroid.testInstrumentationRunnerArguments.m4device=true`
(add `-Pandroid.testInstrumentationRunnerArguments.m4split=DEV` for the 100 DEV cases only). It loads the selected model through the guard-respecting loader, runs with STRICT_OFFLINE, computes the model SHA-256, and writes `report-*.md/json`, `cases-*.csv`, `chart-*.svg` to
`/sdcard/Android/data/com.simone.jarvismobile.debug.beta/files/m4/` → `adb pull` them. It NEVER grants DEVICE_QUALIFIED; memory/document/tool cases are reported NOT EXECUTED on the device (they would need the real stores).
Count each question once: the ZIM subset in B3 and the dataset run are different material and are reported separately.

## D. What the final report must contain
Exact build + model SHA-256 + runtime/backend; cases executed vs not executed; per-category rates; false proofs (see M4_BENCHMARK §3: single-word substitutions); latency p50/p95 per case; load time; PSS before/after; battery/temperature start→end; offline results (A–C of the offline matrix) and what could NOT be observed (other apps, Play services, OS traffic). Anything unmeasured stays `NOT_MEASURED`.
DEVICE_QUALIFIED may be recorded only after B1–B13 and C pass for the exact device + SHA-256 + runtime version + backend; the SHA-256 is re-verified at that moment (not only size/date).
