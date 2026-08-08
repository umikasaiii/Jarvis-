# Device Test Checklist — Phases 4 → 6e (+ automations & agenda)

Everything from Phase 4 onward was written to compile and is packaged by CI, but
is still **pending on-device verification**. This is the walk-through to clear
that: one row per user-visible function, done on the HONOR 200 with the
`latest-debug` APK. Tick a box only after you have seen the *Expected* result
with your own eyes — a green CI run is not a passed test.

How to use it:

- Install the current debug APK, connect AirPods, grant microphone,
  notifications and (where noted) Notification Access.
- Prefer testing the offline path in airplane mode; note anywhere a function
  needs the network (none of the core loop should).
- When a row fails, write the build hash and the symptom in the *Notes* column
  and move on; do not "fix by retry".

Legend: ☐ not tested · ✅ pass · ❌ fail · ⃠ n/a on this device.

---

## Phase 4 — Full TTS, audio focus, follow-up

| # | Function | Steps | Expected | ☐ | Notes |
|---|----------|-------|----------|---|-------|
| 4.1 | Voice selection | Impostazioni › Voce JARVIS → pick an installed system voice | List shows only installed voices; choice persists after reopen | ☐ | |
| 4.2 | Rate / pitch bounds | Move rate and pitch sliders to both extremes, say something | Speech stays intelligible; values clamp, never silent/garbled | ☐ | |
| 4.3 | Speak in AirPods | Ask anything with AirPods connected | Reply plays in AirPods, not the phone speaker | ☐ | |
| 4.4 | Audio focus (music) | Start Spotify, then talk to JARVIS | Music ducks/pauses while JARVIS speaks, resumes after | ☐ | |
| 4.5 | Hands-free follow-up | After a reply, speak again within the follow-up window | Second question captured without pressing anything | ☐ | |
| 4.6 | Follow-up timeout | After a reply, stay silent | Window closes, orb returns to Idle, no hot mic | ☐ | |
| 4.7 | Visible barge-in | While JARVIS is speaking, tap the orb | Speech stops promptly, listening resumes | ☐ | |
| 4.8 | Assistant-role activation | Set JARVIS as device assistant, trigger the assistant gesture | JARVIS opens listening | ☐ | |
| 4.9 | Background speech default OFF | Fresh install, trigger a worker reply with app backgrounded | No spoken reply unless the setting was enabled | ☐ | |
| 4.10 | Background speech FGS | Enable spoken worker replies, background the app, trigger one | A media-playback foreground-service notification is visible while it speaks | ☐ | |
| 4.11 | No always-on hotword | Leave the app idle | Mic indicator never lights on its own | ☐ | |

## Phase 4b — External neural voices (Kokoro / Piper)

| # | Function | Steps | Expected | ☐ | Notes |
|---|----------|-------|----------|---|-------|
| 4b.1 | Kokoro import | Impostazioni › Voce JARVIS → Kokoro → import `.onnx` + `voices-v1.0.bin` | Status becomes «Pronto» only after a real init | ☐ | |
| 4b.2 | Kokoro "Prova voce" | Tap Prova voce | A spoken sample plays through the neural voice | ☐ | |
| 4b.3 | Piper import | Piper → import `<voice>.onnx` + `<voice>.onnx.json` | Config-driven load; `voices-v1.0.bin` is **not** offered for Piper | ☐ | |
| 4b.4 | Piper speech | Ask a multi-sentence question | Long answer synthesised sentence by sentence, streamed, no cut-offs | ☐ | |
| 4b.5 | Per-engine paths | Switch Kokoro↔Piper↔Android and back | Each engine remembers its own file paths | ☐ | |
| 4b.6 | Offline inference | Airplane mode, use a neural voice | Works with no network | ☐ | |
| 4b.7 | Fallback | Select a neural voice with no model imported | Falls back to Android TTS, no crash, clear status | ☐ | |
| 4b.8 | Italian G2P | Speak a word with accents (es. "perché", "città") | Pronounced correctly by the rule-based G2P | ☐ | |

## Phase 5 — Memory V2

| # | Function | Steps | Expected | ☐ | Notes |
|---|----------|-------|----------|---|-------|
| 5.1 | Temporary note | «ricorda solo per oggi che …» | Saved as Temporary; not promoted to the vault | ☐ | |
| 5.2 | Permanent record | «ricordati che il mio codice fiscale professionale è …» (non-secret fact) | Written to `Memoria.md` as a stable record | ☐ | |
| 5.3 | Sensitive marker | Save a fact marked sensitive | Visible sensitive marker shown in the memory screen | ☐ | |
| 5.4 | Credential rejection | «ricorda la mia password …» | Refused; nothing stored | ☐ | |
| 5.5 | CONFIRMING_WRITE | Dictate a delicate fact | JARVIS asks to confirm before writing the vault | ☐ | |
| 5.6 | CRUD screen | Open Memoria, edit and delete a record | Changes persist to `Memoria.md` | ☐ | |
| 5.7 | Obsidian edit resync | Edit `Memoria.md` in Obsidian, reopen app | Change reflected without duplication | ☐ | |
| 5.8 | Bounded retrieval | Ask something covered by a note | Relevant note surfaces; retrieval stays bounded | ☐ | |
| 5.9 | Short recap | Hold a short multi-turn chat | App-private recap keeps context, stays bounded | ☐ | |

## Phase 6 — Controlled Android tools

Arguments must always come from your words. Each row should refuse to guess a
number, destination or title.

| # | Function | Steps | Expected | ☐ | Notes |
|---|----------|-------|----------|---|-------|
| 6.1 | Clock | «che ore sono» | Correct time | ☐ | |
| 6.2 | Battery | «quanta batteria ho» | Correct %, charging only when asked | ☐ | |
| 6.3 | Timer | «metti un timer di 5 minuti» | Timer set | ☐ | |
| 6.4 | Alarm | «sveglia alle 7» | Alarm set at 07:00 | ☐ | |
| 6.5 | Torch on/off | «accendi la torcia» / «spegni la torcia» | Torch toggles | ☐ | |
| 6.6 | Calculate | «quanto fa 12 per 8» | 96 (digits from your words) | ☐ | |
| 6.7 | Open app (allowlist) | «apri Spotify» | Opens; a non-allowlisted app is refused | ☐ | |
| 6.8 | Open settings | «apri le impostazioni Bluetooth» | Bluetooth settings screen | ☐ | |
| 6.9 | Calendar draft (external) | «esporta nel calendario Google un evento…» | Editable ACTION_INSERT draft; app owns Save | ☐ | |
| 6.10 | Dial draft | «prepara una chiamata al 333…» | Dialer opens with number; never auto-calls | ☐ | |
| 6.11 | SMS draft | «prepara un SMS al 333…: arrivo» | Messaging draft; never auto-sends | ☐ | |
| 6.12 | Navigation | «portami a Piazza Navona» | Maps opens with the destination | ☐ | |
| 6.13 | Play media | «riproduci …» | Media search/play intent fires | ☐ | |
| 6.14 | Media control | «metti in pausa la musica» | Active session pauses | ☐ | |
| 6.15 | Notifications read | «leggi le notifiche» | Asks/uses Notification Access; confirmation-gated; nothing logged | ☐ | |
| 6.16 | Vault search | «cerca nei miei appunti …» | Searches only the selected vault | ☐ | |
| 6.17 | No arbitrary intent | Ask for something outside the allowlist | «Non ho uno strumento per farlo», no crash | ☐ | |

## Phase 6b — Personal structured calendar

| # | Function | Steps | Expected | ☐ | Notes |
|---|----------|-------|----------|---|-------|
| 6b.1 | Appointment (timed) | «segna domani alle 15 dal dentista» | Stored as an appointment on the right day | ☐ | |
| 6b.2 | Task (untimed) | «segna per venerdì comprare il pane» | Stored as a dated task, no invented time | ☐ | |
| 6b.3 | "domani" is a date | Reopen the entry | Day stored as a real date, not the word "domani" in the text | ☐ | |
| 6b.4 | Local-first | «aggiungi al calendario …» (no Google mention) | Saved to `Agenda.md`, not the phone calendar | ☐ | |
| 6b.5 | Completion | «ho fatto …» | Exact target shown, then marked done | ☐ | |
| 6b.6 | Seven-day view | Open the dashboard | Week strip reflects the real file | ☐ | |
| 6b.7 | time_until | «quanto manca alle 16» | Deterministic, correct gap | ☐ | |

## Phase 6c — Understanding V3

| # | Function | Steps | Expected | ☐ | Notes |
|---|----------|-------|----------|---|-------|
| 6c.1 | Multi-question turn | Ask two knowledge questions in one breath | Both answered in one coherent reply | ☐ | |
| 6c.2 | Tools before LLM | «che ore sono e quanta batteria ho» | Deterministic tools run; correct combined answer | ☐ | |
| 6c.3 | No role continuations | Ask an open question | No "Tu:"/"JARVIS:" template leakage | ☐ | |
| 6c.4 | Low-confidence safety | Mumble an ambiguous command | No tool executes on low confidence | ☐ | |

## Phase 6d — Persistent response queue

| # | Function | Steps | Expected | ☐ | Notes |
|---|----------|-------|----------|---|-------|
| 6d.1 | Visible progress | Send a heavy chat request | Progress shown; send control becomes Stop | ☐ | |
| 6d.2 | Real cancel | Tap Stop mid-generation | Native inference actually stops | ☐ | |
| 6d.3 | Survives app close | Send a request, close the Activity | Reply still completes; process recreation survives | ☐ | |
| 6d.4 | "Risposta pronta" | Let a backgrounded request finish | Private notification opens the chat | ☐ | |
| 6d.5 | Idempotent write | Force a retry | Chat gets the reply once, not twice | ☐ | |
| 6d.6 | Watchdog | Trigger a stuck generation | 90-second watchdog ends it, no infinite spinner | ☐ | |

## Phase 6e — Reminder engine

| # | Function | Steps | Expected | ☐ | Notes |
|---|----------|-------|----------|---|-------|
| 6e.1 | Due-time alert | Add an entry with an "all'ora" alert a few minutes out | Notification arrives on time, app closed | ☐ | |
| 6e.2 | Morning-of | Set a morning-of alert | Fires at the configured morning hour | ☐ | |
| 6e.3 | N-days-before | Set 1/2/3/7-days-before | Each fires on the right day | ☐ | |
| 6e.4 | Custom time | Set a custom alert time | Fires exactly then | ☐ | |
| 6e.5 | No alert | Leave alerts empty | Nothing fires; bell shows "non impostato" | ☐ | |
| 6e.6 | Survives reboot | Reboot the phone before a due alert | Alert still fires (WorkManager reconciliation) | ☐ | |
| 6e.7 | Edit reconciliation | Change an entry's time/alert | Old notification cancelled, new one scheduled | ☐ | |
| 6e.8 | Delete reconciliation | Delete an entry with a pending alert | No orphan notification | ☐ | |
| 6e.9 | Stable IDs | Edit an entry in Obsidian | Same ID kept, no duplicate reminder | ☐ | |

## Automations — one-shot deferred command (new)

| # | Function | Steps | Expected | ☐ | Notes |
|---|----------|-------|----------|---|-------|
| A.1 | Deferred torch (chat) | «alle 11.45 accendi la torcia» a couple of minutes out | Reply: «Automazione creata…»; torch does **not** turn on now | ☐ | |
| A.2 | Fires once, on time | Wait for the minute, app closed | Torch turns on at the set minute; a notification says what ran | ☐ | |
| A.3 | Spent after firing | Reopen Automazioni | Rule shows a last-execution time and does not re-fire | ☐ | |
| A.4 | Immediate still works | «accendi la torcia» (no time) | Torch turns on immediately (not a rule) | ☐ | |
| A.5 | Appointment untouched | «domani alle 15 dentista» | Filed in the agenda, no automation created | ☐ | |
| A.6 | Deferred media | «alle 22:00 metti in pausa la musica» | One-shot rule; pauses at 22:00 | ☐ | |
| A.7 | Screen text field | Automazioni › «alle 8 accendi la torcia» | Rule created from the text field too | ☐ | |
| A.8 | Allowlist re-check | Disable, then re-enable a saved command rule and test | Runs only through the command allowlist; unknown text does nothing but say so | ☐ | |
| A.9 | Recurring unaffected | «ogni giorno alle 8 ricordami di bere» | Still a recurring notification rule | ☐ | |
| A.10 | Battery/charger unaffected | «quando la batteria scende sotto il 20% avvisami» | Still a conditional rule | ☐ | |

## Dashboard — agenda block (new layout)

| # | Function | Steps | Expected | ☐ | Notes |
|---|----------|-------|----------|---|-------|
| D.1 | Date block | Open Home | Today's weekday, day number and month shown | ☐ | |
| D.2 | Week strip | Look at the strip | Seven days from today; today is ringed | ☐ | |
| D.3 | Entry dots | Add an entry for a future day this week | That day shows a dot | ☐ | |
| D.4 | Timeline order | Have several entries | Rows in chronological order, time on the left | ☐ | |
| D.5 | "In arrivo" badge | Look at a future not-done entry | Cyan «In arrivo» badge | ☐ | |
| D.6 | "Completato" badge | Complete a today entry | Green «Completato» badge, muted styling | ☐ | |
| D.7 | Alert bell | Tap the bell on a row | Opens the reminder-alert editor for that entry | ☐ | |
| D.8 | Empty state | With no upcoming entries | Friendly hint, no crash | ☐ | |
| D.9 | Live update | Add an entry by voice, return to Home | Timeline updates without restart | ☐ | |

---

## Sign-off

- Build hash tested: ________
- Date / tester: ________
- Blocking failures: ________
- Notes for the honesty ledger in `CLAUDE.md`: move a phase from
  "pending device check" to "Verified on-device" only when every row in its
  section is ✅ (or ⃠ with a reason).
