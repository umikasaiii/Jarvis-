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
| LLM prompts/outputs | RAM; local model | Only to a PC endpoint under an opt-in profile, fragments only. |
| Obsidian notes | User's vault (SAF) | Only fragments, only under opt-in remote profile. |
| Secrets (tokens) | Android Keystore | Never. |
| Telemetry | Local only, disableable | Never (no remote telemetry). |

## Sanctioned online exceptions

Two features are explicitly allowed to break offline-first, and only these
two: **weather** (opt-in, Impostazioni › Automazioni) and, later, **live-traffic
travel-time estimates** for a place. Both share the same shape:

- **Off by default.** Nothing is fetched unless the user turns the setting on.
- **Only when the network is actually present.** No fetch is forced; a missing
  connection degrades to "unknown" (never a guess, never a stale value served
  as current — weather has a 6-hour staleness cutoff for exactly this reason).
- **Minimal payload.** Weather sends only a rounded coordinate pair (~1.1 km,
  not the exact fix) and no account, device id, or identifying header — Open-
  Meteo needs no API key.
- **No LLM, no vault, no transcript involved.** The network call is a plain
  HTTP fetch of public forecast/routing data; it never carries anything the
  user typed or said.

Every other feature — STT, the local LLM, TTS, memory, agenda, automations —
stays fully offline, unaffected by this opt-in.

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
