# Privacy Model

**Privacy by default.** No audio, text, note, or telemetry leaves the device
without explicit configuration. No mandatory account. No mandatory cloud. No
hidden recording. No always-on background microphone in the default config.

## Data flows

| Data | Where it lives | Leaves device? |
|------|----------------|----------------|
| Microphone audio | RAM during a session; discarded after | Never (by default). |
| Transcripts | RAM / conversation store (opt-in retention) | Never by default. |
| LLM prompts/outputs | RAM; local model | Only to a PC endpoint under an opt-in profile, fragments only. |
| Obsidian notes | User's vault (SAF) | Only fragments, only under opt-in remote profile. |
| Secrets (tokens) | Android Keystore | Never. |
| Telemetry | Local only, disableable | Never (no remote telemetry). |

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

- **Temporary** — e.g. "ho parcheggiato al B2". Short-lived.
- **Permanent** — e.g. "preferisco 96 GB di RAM". Requires confirmation.
- **Sensitive** — medical data, documents. Requires explicit confirmation; passwords are
  never written to the vault.

Rules: no permanent memory without confirmation; always show the target file and
the exact proposed change; keep a local audit log; allow undo; prefer append or a
new note in `99-Inbox/` over destructive edits.

## Telemetry (§23)

Kept **locally only** and disableable: STT/retrieval/TTS latency, time-to-first-token,
tokens/sec, technical errors, temperature, memory, battery. **Never** logged by
default: audio, transcripts, full prompts, notes, tokens, private addresses.

## User controls

- Explicit data export and full data wipe *(planned, phase 9)*.
- Change the assistant name and system prompt in-app.
- Disable all local telemetry.
- Revoke vault access and any PC/HA token at any time.
