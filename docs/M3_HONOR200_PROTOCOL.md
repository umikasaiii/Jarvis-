# M3 → M4 — Qualification protocol (to be executed; no result below is real)

Two SEPARATE matrices; results are never transferred between devices.

## Common rules
- One large model at a time (never two large models loaded together without explicit consent). Record the model's exact file name, size, SHA-256 (Impostazioni › Modelli › hash), LiteRT-LM version `0.14.0`, backend CPU.
- A model is eligible only if it is a `.litertlm` artifact with official documentation. GGUF/other formats: mark "not supported by this runtime" — do not rename.
- Before each model: battery %, temperature (settings/`dumpsys battery`), free RAM (Android), `adb logcat -b crash` ready.

## Steps per model (A: Honor 200 now; B: future 16 GB Android)
1. Load: record success/failure, the **runtime's message**, initialize time, PSS before/after, peak (if `dumpsys meminfo` captured during load), advice level shown.
2. Short prompt answered; then a prompt with a knowledge source (answer must cite). Record latency and `estimatedTokens` vs `measuredTokens` (trace).
3. Stop during generation → next turn works, no late text.
4. FAST ↔ ADVANCED switch (only if both fit; otherwise record the park/unload decision).
5. Unload; confirm memory returns (PSS).
6. Long conversation (≥ 20 turns): note context drops/reset events.
7. Kill the process during a load (only on request): next start must show the interrupted-load notice and must NOT reload by itself.
8. Network: (a) Wi-Fi on + STRICT_OFFLINE: ask a factual question, open the trace → no `ALLOW` net events; (b) airplane mode on, same; (c) EXPLICIT_ONLINE with Core off: same.
9. Battery % and temperature after 10 minutes of use; response time.
10. On failure: runtime message + `adb logcat` tail + process exit reason.
11. Qualification: only if steps 1–6 pass for the exact (device, SHA-256, version, backend) → record `QualificationEvidence` (M4 task).

## Matrix (fill in M4)
| model file | SHA-256 | size | load ok | init s | PSS Δ | prompt ok | cited | stop ok | switch ok | long conv | strict/online/airplane | batt/temp | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| (Honor 200) … | | | | | | | | | | | | | |
| (16 GB device) … | | | | | | | | | | | | | |
