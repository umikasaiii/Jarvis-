# 0015 — Google Drive backup: Identity/AuthorizationClient, `drive.file`

Status: implemented (CI-verified, pending device check) · 2026-08-16

## Context

ADR 0014 (same day) chose a GMS-free OAuth flow for Google Drive backup sync
— an "Android" OAuth client, but this app deliberately avoids Play Services
elsewhere, so we used a manual RFC 8252 browser-redirect flow against a
"Desktop app" client instead.

The user explicitly reversed this: they configured Google Cloud Console
themselves with an **"Android" OAuth Client ID** (package name + the
**release** signing certificate's SHA-1 — not debug), enabled the Drive API,
and chose the `drive.file` scope, then asked for the integration to go
through `AuthorizationClient`, not the Desktop-client browser flow. This is a
deliberate, informed choice with real infrastructure already behind it, not a
hypothetical — so the app now follows it.

## Decision

Use `com.google.android.gms:play-services-auth`'s Identity/AuthorizationClient
API:

```kotlin
Identity.getAuthorizationClient(context)
    .authorize(AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE), Scope("email")))
        .build())
```

- **No offline access requested** (`requestOfflineAccess()`), which would
  need a *second*, separate **Web application** OAuth client — the user only
  created an Android one. Instead, [`GoogleAuthManager.currentAccessToken`]
  calls `authorize()` fresh whenever a token is needed, including from a
  background Worker with no visible Activity: once the user has granted
  access once, Play Services resolves this **silently, without UI**, for as
  long as access has not been revoked. This is Google's own documented
  pattern for exactly this "no backend server" case, and means the app never
  stores or refreshes a token itself.
- **The interactive path** (first consent, or after a revoke) returns
  `AuthorizationResult.hasResolution() == true` with a `PendingIntent`; the
  UI layer (`BackupScreen`) launches it via
  `ActivityResultContracts.StartIntentSenderForResult()` and hands the result
  back to `GoogleAuthManager.handleResolution()`. This only works from a
  screen with an Activity — matches how the "Collega Google Drive" button
  already worked, and requires no external browser or custom URI scheme
  (`OAuthRedirectActivity` and its manifest intent-filter are removed).
- **No client id/secret in app code or storage at all.** With an "Android"
  client type (unlike "Desktop app"), Play Services resolves the correct
  Google Cloud project from the calling app's package name and signing
  certificate — there is nothing for `DriveCredentialStore` to hold beyond a
  local "authorized" flag and the connected account's cached email.

### Scope change: `drive.appdata` → `drive.file`

This is a real, user-visible change, not just a plumbing detail.
`drive.appdata` (ADR 0014) and `drive.file` are separate, non-overlapping
grants — a `drive.file` token cannot see or write `appDataFolder` at all.
`drive.file` only grants access to files/folders the app itself creates (or
that the user explicitly opens via a picker), so:

- `GoogleDriveRestClient` now finds-or-creates a **"JARVIS Backups" folder at
  the root of the user's own Drive**, and every backup file is uploaded there.
- **This folder is visible in the user's normal Google Drive UI** — the
  opposite of `appDataFolder`, which is hidden. The content is still the same
  AES-256-GCM encrypted archive; Google never sees plaintext either way. See
  `docs/PRIVACY.md`, corrected from the earlier "invisible" claim.
- A cached folder id (`DriveCredentialStore.backupFolderId`) avoids a lookup
  on every sync; it is dropped and re-resolved if a later upload fails outright
  (e.g. the folder was deleted in Drive), rather than failing the same way
  forever.

### Release signing requirement

An "Android" OAuth client is matched by the **exact** signing certificate
that produced the running APK. This means Drive sync **only works with a
release-signed build** using the SHA-1 registered in Google Cloud Console
(`jarvis-release.jks`) — a debug-signed APK (different certificate) will get
`hasResolution()` with no way to complete it, since the debug certificate was
never registered. `docs/GOOGLE_DRIVE_SETUP.md` says this explicitly.

## Consequences

- This app is **no longer fully Play-Services-free** — `play-services-auth`
  is a real, if narrowly scoped, exception, used only for the optional Drive
  backup sync feature. Everything else (STT, LLM, TTS, navigation, place
  automations, ML Kit translation) remains exactly as GMS-independent as
  before; ADR 0013's reasoning for skipping Activity Recognition is
  unaffected — that gap is about a *different* API with no non-GMS
  alternative at all, not a blanket policy this ADR claims to still hold.
- Local backup remains fully offline and fully functional with zero Google
  account, exactly as before — Drive is still only ever a later copy.
- Testing requires a release-signed APK from here on, not the debug build
  every other feature in this repo has been verified with so far.
- The Recovery Key mechanism (`BackupKeyManager`, `RecoveryKeyCodec`) is
  unchanged by this ADR — see ADR 0014's "Recovery key" section, still current.
