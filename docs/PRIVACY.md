# Privacy Model

**Privacy by default.** No audio, text, note, or telemetry leaves the device
without explicit configuration. No mandatory account. No mandatory cloud. No
hidden recording. No always-on background microphone in the default config.

## Data flows

| Data | Where it lives | Leaves device? |
|------|----------------|----------------|
| Microphone audio | RAM during a session; discarded after | Never (by default). |
| Conversation transcript | App-private local chat store | Never. Cleared by “Nuova conversazione”. |
| Structured short memory | App-private `conversation_memory_v2.json` | Never. Cleared with the conversation. |
| Personal calendar/tasks | `JARVIS/Agenda.md` in the chosen vault, or app-private fallback | Never, unless the user explicitly exports one event to another calendar app. |
| Saved places (for "arrivo a…" rules) | `JARVIS/Luoghi.md` in the vault, or app-private fallback | Never. The position is read once on save; geofences are watched on-device. |
| LLM prompts/outputs | RAM; local model | Only to a PC endpoint under an opt-in profile, fragments only. |
| Obsidian notes | User's vault (SAF) | Only fragments, only under opt-in remote profile. |
| Secrets (tokens) | Android Keystore | Never. |
| Telemetry | Local only, disableable | Never (no remote telemetry). |

## Location automations

Location is used for exactly one thing: rules of the form "quando arrivo a
&lt;luogo&gt;". It is **off until the user opts in** — nothing is requested or read
until they grant location and save a place. When they do:

- The phone's position is read **once**, at the moment the user saves a place;
  the app never polls location afterwards.
- Each rule arms a single circular **geofence** the user drew, evaluated by the
  OS on-device. No location leaves the phone and no network is involved.
- Coordinates are written to `JARVIS/Luoghi.md`, a plain list the user can read,
  edit or delete in Obsidian — nothing is hidden in a database.
- `ACCESS_BACKGROUND_LOCATION` is requested only so a saved geofence can fire
  while the app is closed; it is never used for tracking. See ADR 0011.

## Privacy profiles (§13)

The user chooses how much may reach a companion PC:

- **DEVICE_ONLY** — nothing ever leaves the device.
- **PREFER_DEVICE** — device first; PC only if explicitly needed.
- **PREFER_PC_TRUSTED_NETWORK** — offload complex requests to the PC on a trusted network.
- **ALLOW_PC_VIA_VPN** — as above, also over VPN.
- **MAX_PRIVACY** — strictest; equivalent to device-only with extra guards.

Independently, memory sharing to a remote is one of: **NONE**,
**SELECTED_FRAGMENTS_ONLY** (default when remote is allowed), **ALLOWED**. The
whole vault is **never** auto-sent anywhere. Every routing decision records the
target, reason, timeout, and exactly what would be shared, shown before sending.

## Memory-write classification (§14)

- **Temporary** — e.g. "ho parcheggiato al B2". App-private and conversation-scoped.
- **Permanent** — e.g. "preferisco 96 GB di RAM". Exact confirmation, then a stable Markdown record.
- **Sensitive** — medical data, documents. Requires explicit confirmation; passwords are
  never written to the vault.

Rules: no chat/voice memory write without confirmation; the prompt shows the
exact text and target; passwords, PINs, OTPs, tokens/API keys and seed phrases
are rejected. The Memory screen supports edit/delete and Obsidian remains the
source of truth. A separate action audit/export is still planned for hardening.

## Telemetry (§23)

Kept **locally only** and disableable: STT/retrieval/TTS latency, time-to-first-token,
tokens/sec, technical errors, temperature, memory, battery. **Never** logged by
default: audio, transcripts, full prompts, notes, tokens, private addresses.

## User controls

- Explicit data export and full data wipe *(planned, phase 9)*.
- Change the assistant name and system prompt in-app.
- Disable all local telemetry.
- Revoke vault access and any PC/HA token at any time.
- Choose an installed offline voice, or disable spoken background answers
  (disabled by default so private content is not read aloud unexpectedly).
