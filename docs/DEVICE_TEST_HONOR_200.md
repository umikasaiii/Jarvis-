# Device Test Guide — HONOR 200 (MagicOS, Snapdragon 7 Gen 3)

## Setup

1. Enable Developer options → USB debugging.
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. Grant microphone, notifications and Bluetooth permissions when prompted.
4. Add the **JARVIS** tile to Quick Settings (edit tiles → drag JARVIS in).
5. Pair AirPods and connect them.

## MagicOS background execution

MagicOS aggressively kills background apps. If the listening service is stopped
unexpectedly:

- Settings → Battery → App launch → **JARVIS Mobile** → set to **Manage manually**
  and enable *Auto-launch*, *Secondary launch*, *Run in background*.
- Settings → Apps → JARVIS Mobile → Battery → **Unrestricted**.

We deliberately do **not** request a blanket battery-optimization exemption; the
above is user-controlled and reversible.

## Long-press-volume activation (optional, external)

Not built in (no Accessibility abuse). To bind a hardware gesture, use **Tasker**
(or a MagicOS gesture) to open `jarvis://listen` or launch the app's listening
Activity. Example Tasker: Event → Volume Long Press → Task → *Browse URL*
`jarvis://listen`.

## 20-step functional checklist (§25)

| # | Step | Expected |
|---|------|----------|
| 1 | AirPods connected | Home shows "AirPods". |
| 2 | Airplane mode ON | App still functional (offline path). |
| 3 | Start via Tile | Explicit session starts, notification appears. |
| 4 | Listening tone | Short cue plays. |
| 5 | Dictate in Italian | Captured. |
| 6 | End of sentence | VAD auto-finalizes *(phase 2)*. |
| 7 | Local transcription | Correct text *(phase 2)*. |
| 8 | Local model reply | Textual answer *(phase 3)*. |
| 9 | TTS in AirPods | Spoken through AirPods. |
| 10 | Second question in follow-up | Handled within the window. |
| 11 | Disconnect AirPods mid-session | Graceful. |
| 12 | Fallback to phone | Route switches, shown in UI, no crash. |
| 13 | Screen off | Session continues. |
| 14 | Battery saver | Still works (maybe slower). |
| 15 | Manual interrupt | Stops promptly. |
| 16 | Incoming call | Audio focus released; app recovers. |
| 17 | Model not loaded | Clear "Modello non caricato". |
| 18 | Insufficient RAM | Load blocked with explanation. |
| 19 | Vault unavailable | Clear message, no crash. |
| 20 | Network vanishes during PC request | Fast fallback to local, no long hang. |

Steps 6–8/17–20 depend on later phases; verify as they land.

## Acceptance scenarios A–F

- **A (fully offline):** internet off, PC off, AirPods on, vault available →
  Tile → speak Italian → local model reply in AirPods → can query an indexed note.
- **B (no AirPods):** phone mic/speaker used, no crash, routing change shown.
- **C (memory):** "Ricordati che per il PC preferisco 96 GB di RAM." → app proposes
  a change, shows target + content, writes only after confirmation.
- **D (PC available):** complex question sent to authorized server with visual
  indication and local fallback if it doesn't respond.
- **E (Home Assistant):** innocuous command executes and is verified via returned
  state; a security command requires biometrics.
- **F (privacy):** with the network blocked, the local path works and **no**
  unexpected external host appears in network logs.

Record measured latencies (press→tone, STT, TTFT, tokens/sec, TTS) and thermal/
battery behavior in a results table per run.

## FASE 2A.7 — JARVIS Release Gate: Device Acceptance Checklist (Conversational Engine)

Companion to the automated JVM release gate (`cd core && ./gradlew test` —
grounding, capability routing, keyword-matching, parser-safety and date/period
invariants are pinned there and run on every push). The 15 scenarios below are
the ones that **cannot** be certified without a real Honor 200: Health
Connect's actual permission dialog and real synced data, real airplane-mode
network transitions, a real JARVIS Core PC instance turning on/off mid-session,
real mode switching with the model's own native `Conversation`/KV cache, and
the actual flashlight hardware. Enable Impostazioni → Diagnostica's
conversational-engine debug panel before starting (it shows `routingPath`,
`modelRounds`, `toolFamiliesSelected`, `requiredGroundingFamilies`/
`satisfiedGroundingFamilies`, `groundingBlockReason`, `networkAvailable` per
turn — read it after every step instead of guessing from the spoken reply
alone).

**FASE 2A is done only when every row below is ✅ AND the automated suite is
green — a green CI run alone is `AUTOMATED RELEASE GATE ✅`, never
`FASE 2A — FAST COMPLETATA` by itself.**

| # | Input | Expected `routingPath` | Expected tool/source | Expected `modelRounds` | Expected grounding | Expected failure behavior | Pass/Fail |
|---|-------|------------------------|-----------------------|------------------------|---------------------|----------------------------|-----------|
| 1 | "Ciao, come stai?" | `FAST_LLM`/`BRAIN` (no capability match) | none | model answers directly (>0 is fine — no tool round needed) | `requiredGroundingFamilies` empty | n/a — natural reply, no JSON, no boilerplate "Come posso aiutarti?" | |
| 2 | "Che impegni ho domani?" | `FAST_PATH` (existing agenda fast path) | `list_agenda`, real `AgendaRepository` | `0` | AGENDA required+satisfied | if no agenda entries: says so plainly, never invents one | |
| 3 | "Che tempo fa domani?" | `CAPABILITY_FAST_PATH` | `get_weather`, real `WeatherManager` | `0` | WEATHER required+satisfied | offline/source failure → honest fail message, no invented forecast | |
| 4 | "Quante ore ho dormito questa settimana?" | `CAPABILITY_FAST_PATH` | `get_health_summary` `period=week`, real `HealthConnectManager` | `0` | HEALTH required+satisfied | missing days shown as missing, never counted as 0h | |
| 5 | "Qual è la mia media sonno questa settimana?" | `CAPABILITY_FAST_PATH` | `get_health_summary` `period=week` | `0` | HEALTH required+satisfied | same weekly aggregate as #4 (by design — "media" and "questa settimana" are the same window) | |
| 6 | Weather con rete OFF (airplane mode), poi "Che tempo fa domani?" | `CAPABILITY_FAST_PATH` | `get_weather` tool call attempted, rejected `NetworkRequiredButOffline` | `0` | WEATHER required, **not** satisfied | "Non riesco ad accedere a quel dato in questo momento." — no invented weather | |
| 7 | Revoca il permesso Health Connect da Impostazioni Android, poi "Quanto ho dormito stanotte?" | `CAPABILITY_FAST_PATH` | `get_health_summary` `period=last_night`, `health_permission_missing` | `0` | HEALTH required, **not** satisfied | honest fail message, never a fabricated number | |
| 8 | Ripristina il permesso Health Connect, ripeti "Quanto ho dormito stanotte?" | `CAPABILITY_FAST_PATH` | `get_health_summary` `period=last_night`, real last-night reading (or honest "nessun dato" if not yet synced) | `0` | HEALTH required+satisfied (or honestly unsatisfied if truly no data) | never falls back to the week's average for "stanotte" | |
| 9 | "Accendi la torcia." | (existing device fast path / `CommandMatcher`) | `flashlight`, real hardware toggle | n/a | DEVICE required+satisfied | — | |
| 10 | "Accendi la luce della camera." | `HOME_CONTROL_UNSUPPORTED` | none — **never** `flashlight** | `0` | n/a (guard fires before grounding) | "Non ho ancora un'integrazione per il controllo della casa…" — flashlight must NOT turn on | |
| 11 | Con JARVIS Core (PC) acceso e raggiungibile, un messaggio qualunque | `lastRoute` in Diagnostica = `CORE FAST`/`CORE BRAIN` | remote Ollama via `jarvis-core` | n/a | per-request as above | — | |
| 12 | Core spento/non raggiungibile, stesso messaggio | `lastRoute` = `LOCAL (fallback…)` | local Gemma via `LlmRouter` | n/a | per-request as above | fallback is silent to the user (one coherent reply, never two) | |
| 13 | Spegni Core (o disconnetti il PC dalla rete) A METÀ di una richiesta in corso | `lastRoute` ends as `LOCAL (fallback dopo Core: …)` | local fallback completes the turn | n/a | per-request as above | no crash, no hung turn, next request may return to `CORE FAST` once Core is back | |
| 14 | Modalità Classica: "Il mio codice temporaneo è ALFA" → passa a Conversazionale, fai 1-2 richieste qualunque → torna a Classica → "Qual era il codice temporaneo?" | n/a (Classic mode has no `routingPath`) | Classic's own persistent `Conversation` | n/a | n/a | Classic must still answer "ALFA" — its native session must survive the round-trip through Conversational mode | |
| 15 | "Considerando come ho dormito e gli impegni di domani, a che ora dovrei andare a letto?" | `BRAIN` (multi-family, never `CAPABILITY_FAST_PATH`) | both `get_health_summary` and `list_agenda`/agenda tool actually executed | `>0` | HEALTH+AGENDA both required, both must be satisfied before an answer | if either source fails, the reply must say so — never reasons as if it had both when it only got one | |

### Casi che NON possono essere certificati senza l'Honor 200 (§ FASE 2A.7)

- Il comportamento reale del modello locale (Gemma) e di Core/Ollama davanti
  al protocollo JSON — se rispetta davvero il contratto, se produce mai testo
  che elude comunque le regex del parser — è verificabile solo eseguendo un
  vero modello, non in questo ambiente di sviluppo (nessuna GPU/NPU, nessun
  modello nel repository).
- Il vero stato dei permessi Health Connect (righe 7-8) e la vera
  sincronizzazione Honor Health → Health Connect.
- Le vere transizioni di rete (aereo ON/OFF, righe 6, 11-13) e un vero
  processo JARVIS Core in esecuzione su un PC reale.
- La torcia hardware reale (riga 9) e la garanzia che "luce della camera"
  (riga 10) non la accenda per errore su un dispositivo reale.
- La sopravvivenza della `Conversation` nativa di Modalità Classica attraverso
  un vero cambio di modalità (riga 14) — dipende dal comportamento reale di
  `LitertLmEngine`, non riproducibile senza il modello caricato su device.
- Process death/Doze reali durante un turno multi-round.
