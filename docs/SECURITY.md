# Security & Threat Model

## Assets to protect

- Home Assistant token; companion-PC token.
- Sensitive metadata; conversation history; the Obsidian vault; backups.
- The microphone (must never be silently active).

## Adversaries / threats considered

| Threat | Mitigation |
|--------|-----------|
| Malware / other apps reading secrets | Secrets only in Android Keystore-backed store; `allowBackup=false`; secrets excluded from backup/device-transfer rules; never in `BuildConfig`, logs, or the repo. |
| Secrets leaking via logs | `LogRedactor` masks tokens/emails/IPs/phones; content is logged only as length+hash placeholders; release builds log technical events only. |
| Model-driven code execution | The model can only call **registered tools**. No shell, no arbitrary Intents/classes/URLs, no dynamic code loading. `calculate` is a hand-written parser, not `eval`. |
| Over-broad file access | No `MANAGE_EXTERNAL_STORAGE`/`QUERY_ALL_PACKAGES`. Vault access is a single SAF tree URI the user grants; nothing outside it is read. |
| Unauthorized network egress | Offline path never touches the network. Remote calls happen only under an opt-in `PrivacyProfile`, or the two explicitly sanctioned exceptions (weather; later live-traffic ETA — see `docs/PRIVACY.md`), each off by default and sending only rounded coordinates, no account/device id. Router records exactly what would be shared before sending. |
| TLS interception | No trust-all TLS, no certificate bypass. User may install a chosen CA if needed (e.g. self-hosted HA); we never disable verification. |
| Sensitive action without intent | `CONFIRMING_WRITE` and higher policies require confirmation. `HOME_SECURITY`/`DESTRUCTIVE` are blocked until the biometric UI exists. Calls/SMS/calendar are drafts in system apps and cannot be sent/saved silently. |
| Notification exposure | Android Notification Access must be granted in system settings; each read is explicitly confirmed, bounded, never persisted and hidden from logs. |
| Shoulder-surfing / lost device | Optional biometric app lock, session timeout, optional screenshot block on sensitive screens. |
| Tampered models | SHA-256 verification on import; licenses shown before download; models never auto-downloaded. |
| Overlay abuse (tapjacking) | Modalità Guida's overlay windows use `FLAG_NOT_FOCUSABLE` only — never `FLAG_NOT_TOUCHABLE` tricks to intercept taps meant for another app — and are sized to just JARVIS's own panels; the large gap over Maps has no window at all, so it is never JARVIS intercepting what looks like a Maps tap. |

## Controls checklist (§21)

- [x] Secrets in Keystore only; none in `BuildConfig`/repo *(SecretStore lands phase 0+)*.
- [x] No conversation text in logcat in release; automatic log redaction (`LogRedactor`, tested).
- [ ] Explicit data export; full data wipe.
- [ ] Optional biometric lock; session timeout; optional screenshot block.
- [x] `allowBackup=false`; secrets excluded from backup (`data_extraction_rules.xml`).
- [x] Strict URI validation; no trust-all TLS; no WebView auth; no dynamic code exec.
- [ ] Model signature/checksum verification on import *(phase 3)*.
- [x] SBOM / dependency & license list (`THIRD_PARTY_NOTICES.md`, version catalog).

Legend: [x] designed/partially implemented, [ ] planned. See `CLAUDE.md` phase state.

## Permissions rationale

| Permission | Why | Notes |
|-----------|-----|-------|
| `RECORD_AUDIO` | Voice capture | Only inside a user-started, FGS-backed session. |
| `FOREGROUND_SERVICE` + `_MICROPHONE` | Visible mic use | Android 14+ typed FGS. |
| `FOREGROUND_SERVICE_DATA_SYNC` | Persistent local LLM work | WorkManager processing notification. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Opt-in spoken background answer | Declared only while the visible worker speaks. |
| `POST_NOTIFICATIONS` | Session, response and agenda notifications | Android 13+. Private preview is off by default. |
| `BLUETOOTH_CONNECT` | Route to AirPods, read device name | No location, no scanning. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Opt-in PC/HA; opt-in weather (Open-Meteo, no API key) | Unused on the offline path; weather sends only a rounded coordinate pair, degrades to "unknown" with no connection. |
| `ACCESS_FINE_LOCATION` / `_COARSE_LOCATION` | Offline navigation GNSS; place automations | On-device only; no network, no scanning. |
| `FOREGROUND_SERVICE_LOCATION` | Navigation guidance with the screen off | Declared only while a visible navigation session runs. |
| `ACCESS_BACKGROUND_LOCATION` | Place automations ("arrivo a &lt;luogo&gt;") fire while the app is closed | Opt-in per place rule; geofence evaluated on-device, no tracking, no network. `CapabilityManager` gates rules on this grant. |
| `SYSTEM_ALERT_WINDOW` | Modalità Guida's small overlay windows over Google Maps | Requested via `Settings.canDrawOverlays()` only when the user starts Modalità Guida; the mode reports it is missing rather than drawing a fake overlay. |

Not requested: `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`,
`CALL_PHONE`, `SEND_SMS`, `READ_CONTACTS`, `READ_CALENDAR`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, any Accessibility service. MagicOS
background-kill guidance lives in `docs/DEVICE_TEST_HONOR_200.md` instead of a
blanket battery-optimization exemption.
