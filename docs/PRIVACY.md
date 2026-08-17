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
| Google Drive connection state (whether authorized, the connected account's email, the "JARVIS Backups" folder id) | `DriveCredentialStore` — `EncryptedSharedPreferences`, Keystore-backed; separate from the plaintext DataStore every other setting uses | Not stored elsewhere. No client id/secret or refresh token is held by the app at all — Play Services manages the OAuth exchange itself (opt-in, off by default; see docs/DECISIONS/0015). |
| Backup content (JARVIS's own data — vault, memory, Room DB, prefs, agenda) | AES-256-GCM encrypted archive, key never in the archive | Only to a "JARVIS Backups" folder JARVIS creates in the user's **own, visible** Google Drive, only when cloud sync is explicitly turned on and an account is connected. Never in plaintext. |
| Notification content read by Modalità Guida | RAM only, read on demand from the OS notification listener | Never. Not persisted, not logged; disappears the moment Notification Access is revoked. |

Modalità Guida (the driving overlay) is opt-in, foreground-service-backed like the
main listening session, and introduces no second microphone, notification, or
media pipeline — it reads the same wake word, STT/TTS, `NotificationListenerService`
and `MediaSessionManager` the rest of the app already uses. Replying to a message
through it is a real send (via the notification's own quick-reply action) and is
confirmed like every other outbound message in this app.

## Sanctioned online exceptions

A small, explicit list of features are allowed to break offline-first — no
others: **weather** (opt-in, Impostazioni › Automazioni), **online map tiles**
for JARVIS Drive (opt-in, Impostazioni › Navigazione offline › Mappe offline),
**live traffic** on JARVIS Drive's own internal map (opt-in, same screen),
**online destination search** and **online routing** (same toggle,
fill-in only — see below; unlike the first three, these two send the typed
query resp. the start/destination coordinates, so they don't share the
"nothing typed or routed" guarantee the others make). The first three share
the same shape:

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

**Online map tiles** specifically: when on, and only when no offline region
covers the current position, JARVIS Drive fetches map tiles from
[OpenFreeMap](https://openfreemap.org) — free, unlimited, no account, no API
key. The request is a plain anonymous tile fetch; it never carries the user's
saved places, destination history, search queries, or anything typed/said.
Turning the toggle off (the default) never breaks the map — it just means an
uncovered position shows the existing "nessuna mappa offline" state instead of
an online tile, same as before this existed.

**Live traffic** specifically: when on, and only when the user has saved
their own [TomTom Developer](https://developer.tomtom.com) API key (free
tier, no payment method required), JARVIS Drive's internal map fetches
TomTom's vector traffic-flow tiles and draws them as a colored overlay on
JARVIS's own map — never as an overlay on the separate Google-Maps-based
Modalità Guida. The API key is entered once in Impostazioni › Navigazione
offline › Mappe offline, stored Keystore-encrypted
(`TrafficApiKeyStore`, never in the plaintext DataStore every other setting
uses), and sent only as a query parameter on TomTom's own tile requests —
never to any other host, never alongside anything typed or said. Turning
the toggle off, or never saving a key, leaves the map exactly as it is
today: no traffic layer, no request made.

**Online destination search is a different shape of exception — flagged
separately because it breaks the "never anything typed or said" guarantee
the other exceptions make.** When live traffic is on (same TomTom key,
same toggle — Impostazioni › Navigazione offline › Mappe offline), JARVIS
Drive's "Cerca destinazione" only calls TomTom's online search
(`TomTomSearchFetcher`) as a **fill-in**, and only when the offline index
does not already return enough results: the offline
`PlaceSearchRepository` always runs first and its results are never
dropped or replaced. When the online fill-in does run, it sends the
**typed search text** plus a rounded current position to TomTom, so this
one path is a real, deliberate exception to "nothing typed leaves the
device." It never sends the conversation, the vault, saved places, or
destination history — only the text the user is actively typing into that
one field, for that one request. Turning the toggle off, or never saving a
key, keeps destination search exactly as it was before this existed:
offline-only, and an honest "nessun risultato" when the offline index
doesn't have it.

**Online routing** (same toggle, same TomTom key) is the fourth and last
piece of this same exception family. JARVIS Drive's own routing engine
(`AStarRouterEngine`) is offline, but it can only compute a route inside a
downloaded map region — with no region installed (the now-common case
since the map itself can render from online tiles) it has nothing to route
on. When that happens, and only while live traffic is on,
`TomTomRoutingEngine` calculates the route online instead, sending the
**start and destination coordinates** to TomTom for that one request —
never the vault, memory, agenda, or anything else. Turning the toggle off
leaves route calculation exactly as it was before this existed: offline
regions only, an honest "percorso non disponibile" everywhere else.

Every other feature — STT, the local LLM, TTS, memory, agenda, automations —
stays fully offline, unaffected by this opt-in.

## Optional cloud backup (Google Drive)

A third, differently-shaped exception: **Google Drive backup sync**
(Impostazioni › Backup e sincronizzazione), opt-in and off by default.

- **Never required.** Local backup — the source of truth — works fully
  offline with no Google account at all; Drive is only ever a later, optional
  copy (see `docs/DECISIONS/0015-drive-authorization-client.md`).
- **`drive.file` scope only.** JARVIS can only read/write files and folders it
  created itself — a "JARVIS Backups" folder it creates in the user's own
  Drive — never the user's other Drive files, never broader Drive access.
  Unlike an earlier design (ADR 0014, superseded), **this folder is visible**
  in the user's normal Drive UI, not hidden — the content inside it is still
  the same encrypted archive either way.
- **Uses Play Services** (`com.google.android.gms:play-services-auth`,
  Identity/AuthorizationClient), a deliberate, narrowly scoped exception to
  this app's general Play-Services-free stance — see ADR 0015 for why. No
  other feature in the app depends on Play Services.
- **Content stays encrypted end to end.** What reaches Drive is the same
  AES-256-GCM archive already written locally — Google never sees plaintext.
- **No client id/secret anywhere.** The OAuth client is an "Android" type,
  matched by this app's package name and signing certificate (a separate
  registration for the debug and release variants — see
  `docs/GOOGLE_DRIVE_SETUP.md`) — nothing for the app or the user to hold or
  paste in.
- **A recovery key is required to read old cloud backups on a new device**,
  shown only on explicit request and never written into the cloud archive it
  protects (`BackupKeyManager`, `RecoveryKeyCodec`).

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
