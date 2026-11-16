# M3 — Offline policy (STRICT_OFFLINE) and census of network adapters

## Policy
`core/net/NetworkPolicy.kt` (pure, JVM-tested) decides per operation; `app/net/OfflineGate.kt` enforces it at the boundary:
- **STRICT_OFFLINE** (Impostazioni › Core: «Solo offline»): JARVIS's own code makes **no network request at all**, whatever integration is enabled.
- **EXPLICIT_ONLINE** (default): an operation may use the network only if the user enabled that integration (each feature still checks its own opt-in first); the assistant path (chat, knowledge, documents, memory, tools) never uses the network by itself; an operation not in the census (`UNKNOWN`) is refused.
- The gate is **fail-closed**: until the persisted mode is read at start-up the effective mode is STRICT_OFFLINE.
- A call whose OkHttp `Call` was cancelled is refused (no late attempt).
- The mode is **not** read from Android's "connected" flag.

## Census (CODE VERIFIED by `OfflineGuardCensusTest`: 10 OkHttp clients, all through `.guarded(op)`; no `HttpURLConnection`, `openConnection`, `openStream`, raw sockets or WebView in app sources)
| adapter | operation | gate |
|---|---|---|
| `JarvisCoreClientImpl` (Core PC: chat, health, probe, capabilities, events) | `CORE_REMOTE_CHAT` | OkHttp interceptor **and** `AiRouter` routing (`strictOffline()` → LOCAL, traced) — covers `tryRemoteChat`, `JarvisBrain.tryRemoteReply`, `AiRouter.generate/stream` |
| `OpenMeteoWeatherSource` | `WEATHER` | interceptor |
| `OnlineMapStyleFetcher` | `MAP_STYLE_ONLINE` | interceptor |
| `TomTomTrafficFetcher` / `TomTomSearchFetcher` / `TomTomRoutingEngine` | `MAP_TRAFFIC` / `MAP_SEARCH` / `MAP_ROUTING` | interceptor |
| `RegionManager` (map region downloads) / `RegionCatalog` | `MAP_REGION_DOWNLOAD` / `MAP_CATALOG` | interceptor |
| `GoogleDriveRestClient` | `BACKUP_DRIVE` | interceptor |
| `GoogleAuthManager` (own HTTP part) | `AUTH` | interceptor |
| `TranslationModelManager.download` (ML Kit model download) | `TRANSLATION_MODEL_DOWNLOAD` | explicit `OfflineGate.allows` before the call (the download itself runs in Play services) |
| Home Assistant | — | not implemented in the app (phase 7 not started): no client exists |
| Model download inside the app | — | none exists: models are imported by the user |

## Out of coverage (declared, NOT claimed)
- Traffic of **other apps** started through Intents (Organic Maps, Kiwix, browser, Google Maps…): JARVIS does not control them.
- **Google Play services** components: Drive authorisation (`AuthorizationClient`), ML Kit's own download engine (only its *initiation* is gated), Play Services updates.
- **MapLibre's native HTTP stack** when it renders an online style/tiles it was given by `OnlineMapStyleFetcher` (the style fetch is gated; tile fetching by the native view is not intercepted — with STRICT_OFFLINE the online style is never fetched, so no online source is spliced into the map).
- The OS and other processes. The tests observe **JARVIS's instrumented adapters** (loopback server on the emulator), not packets on the wire; airplane mode was **not** exercised by an automated test (PENDING, see the Honor 200 protocol).
- `INTERNET` stays in the manifest: optional integrations need it.

## Evidence levels
- CODE VERIFIED: census, fail-closed init, router gates.
- JVM TESTED: `M3NetworkPolicyTest`, trace privacy.
- ANDROID EMULATOR TESTED: `M3OfflineInstrumentedTest` (real OkHttp + production `CoreClient` + real settings against a loopback socket counter; STRICT → 0 connections, EXPLICIT → connection; cancelled call → 0; every operation blocked in STRICT).
- REAL DEVICE VERIFIED: none.

## Trace (content-free, bounded)
`OfflineTrace` (200 events, in memory, rotation by overwrite). Fields: time, technical request id (UUID), slot, `net:<op>`/`llm:<event>`, mode, ALLOW/BLOCK, cause, result category, duration, whitelisted numeric facts (`estimatedTokens`, `measuredTokens`, `configuredWindow`, `plannedWindow`, `pssBeforeMb`, `pssAfterMb`, `totalMb`, `attempts`). Every string field is written from a **closed vocabulary** (anything else becomes `other`/`redacted`): no prompt, message, document text, memory, credential, URL, host or IP can be stored. Export: `OfflineTrace.preview()` then `exportForUser(confirmed = true)` — returns the exact previewed JSON; nothing is sent anywhere. **UI for the export is PENDING** (internal interface tested only).
