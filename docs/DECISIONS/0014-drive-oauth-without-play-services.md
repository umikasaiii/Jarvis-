# 0014 — Google Drive backup: real OAuth, still without Play Services

Status: implemented (CI-verified, pending device check) · 2026-08-16

## Context

Phase 6h shipped local-first backup with `CloudBackupProvider` as an interface
and an honest `GoogleDriveBackupProvider` stub that always reported itself as
"not configured" — real Drive sync needed OAuth, and this project deliberately
avoids Google Play Services (see ADR 0013: Activity Recognition was skipped
specifically because no AOSP equivalent exists, only a GMS one).

The standard way most Android apps get a Drive-scoped OAuth token — Google
Sign-In, or the newer Credential Manager + Identity Authorization Client — is
itself Play-Services-backed (`com.google.android.gms:play-services-auth`,
directly or through a thin AndroidX wrapper). Adopting it here would mean the
one deliberately GMS-free app in this repo quietly stops being one, for a
feature that is supposed to be optional and secondary to begin with.

## Decision

Use RFC 8252's OAuth 2.0 native-app flow instead: open Google's consent page
in an external browser (Custom Tab-free — a plain `ACTION_VIEW` Intent, same
shape as the app's existing navigation/Maps launches), catch the redirect on a
private-use custom URI scheme (`com.simone.jarvismobile:/oauth2redirect`), and
exchange the authorization code for tokens with Authorization Code + PKCE over
plain HTTPS (`OkHttpClient`, already a dependency). No Google SDK, no Play
Services, no WebView (Google disallows embedded-WebView OAuth for this flow
anyway).

This requires a **"Desktop app" OAuth Client ID**, not an "Android" one — the
"Android" client type in Google Cloud Console is meant for the Play-Services
Credential Manager / Google Sign-In SDKs, verifies the caller by package name
+ SHA-1 through Play Services, and does not support a manually-caught custom
redirect. "Desktop app" clients support exactly this native-app pattern. See
`docs/GOOGLE_DRIVE_SETUP.md` for the exact console steps.

The client id/secret are never hardcoded or committed: the user creates their
own Google Cloud project and pastes both into Impostazioni › Backup e
sincronizzazione, where they are kept in `DriveCredentialStore`
(`EncryptedSharedPreferences`, Keystore-backed) — never in the plaintext
DataStore the rest of Settings uses, never in the repository, never in the APK.

### Recovery key (restore on a new device)

Local backups are encrypted with a device-Keystore-resident AES-256 key —
correct for local-only protection, but Keystore keys cannot be exported by
design, so a Keystore-only scheme could never let a *different* device decrypt
an old cloud backup. `BackupKeyManager` introduces the standard envelope fix:
a random, software-held 256-bit content key actually encrypts backup archives;
the Keystore key only wraps that content key for local storage. The content
key can be exported once, deliberately, as a human-copyable recovery string
(`RecoveryKeyCodec`, `:core`, Crockford Base32 + a CRC-8 typo check) — shown
only on request, never written anywhere by JARVIS itself, and never stored
inside the cloud archive it protects.

## Consequences

- Google Drive backup stays a strictly optional, GMS-free feature — the whole
  app, including this feature's own local half, works with zero Google
  account, matching `docs/PRIVACY.md`.
- The user carries a one-time manual setup cost (their own Google Cloud
  project) that a Play-Services flow would have hidden. Documented in full in
  `docs/GOOGLE_DRIVE_SETUP.md`.
- Losing the recovery key does not weaken today's device; it only means a
  future device cannot read old cloud backups without it — an explicit,
  documented tradeoff of the envelope design, not an accident.
- `drive.appdata` scope only: JARVIS's own hidden folder, invisible in the
  user's normal Drive UI, removed automatically on disconnect/uninstall of
  Drive access — never the user's visible files.
