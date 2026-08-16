# Google Drive backup — Google Cloud Console setup

Google Drive sync for JARVIS's backups (Impostazioni › Backup e sincronizzazione
› Google Drive) is entirely optional and off by default — the whole app,
including local backup, works with zero Google account. This is only needed if
you want an encrypted copy of your JARVIS backups in your own Google Drive.

This uses Android's Identity/`AuthorizationClient` API (`play-services-auth`)
against an **"Android" OAuth client** — matched by this app's package name
and signing certificate, not by a client id/secret embedded anywhere. See
`docs/DECISIONS/0015-drive-authorization-client.md` for why (and how this
differs from the "Desktop app" approach in the now-superseded ADR 0014).

**Important — this only works with a release-signed build.** An "Android"
OAuth client is tied to one exact signing certificate's SHA-1. A debug APK
(different certificate) cannot complete authorization no matter how the
Cloud Console is configured — see `docs/DECISIONS` for the release keystore.

## 1. Create a Google Cloud project

1. Go to [console.cloud.google.com](https://console.cloud.google.com) and sign
   in with any Google account.
2. Create a new project (any name, e.g. "JARVIS Backup").

## 2. Enable the Drive API

1. In the project, go to **APIs & Services › Library**.
2. Search for **Google Drive API** and click **Enable**.

## 3. Configure the OAuth consent screen

1. **APIs & Services › OAuth consent screen**.
2. User type: **External** (unless you have a Google Workspace org and want
   **Internal**). App name: anything (e.g. "JARVIS"). Support email: yours.
3. **Scopes**: add `https://www.googleapis.com/auth/drive.file` and `email`.
   `drive.file` only lets JARVIS see files/folders **it creates itself** (the
   "JARVIS Backups" folder) — never your other Drive files. Unlike
   `drive.appdata`, this folder **is visible** in your normal Drive.
4. **Test users** (while the app is in "Testing" publishing status, which is
   fine indefinitely for personal use): add your own Google account's email.
   Without this, Google will refuse to authorize it.

## 4. Create the OAuth Client ID (Android type)

1. **APIs & Services › Credentials › Create Credentials › OAuth client ID**.
2. Application type: **Android**.
3. **Package name**: `com.simone.jarvismobile` (the release `applicationId` —
   not `com.simone.jarvismobile.debug`, which is the debug build's separate id).
4. **SHA-1 certificate fingerprint**: the SHA-1 of your **release** keystore
   (`jarvis-release.jks`), not the debug one. Get it with:
   ```
   keytool -list -v -keystore jarvis-release.jks -alias jarvis-release
   ```
5. Click **Create**. No client secret is issued for this type — Play Services
   resolves the correct project from your app's package name + certificate,
   nothing to paste into JARVIS.

## 5. Connect JARVIS

1. Build and install a **release**-signed APK (see `keystore.properties` /
   the `JARVIS_RELEASE_*` env vars in `app/build.gradle.kts`; a debug build
   cannot complete this).
2. Open JARVIS › Impostazioni › Backup e sincronizzazione.
3. Turn on **Sincronizza sul cloud**, pick **Google Drive** as the provider.
4. Tap **Collega Google Drive**. If Play Services already has consent cached
   this completes instantly with no UI; otherwise Google's own account/consent
   screen opens, you approve it, and you're back in JARVIS.
5. Impostazioni shows the connected account's email once it completes.

## 6. Before you ever need it: save the recovery key

Backups are encrypted with a key that lives only on this phone. In the same
screen, under **Chiave di recupero**, tap **Mostra la chiave di recupero** and
save the string somewhere durable (password manager, printed and stored
safely). You will need it to read your cloud backups from a **different**
phone — Android Keystore keys cannot be exported, by design, so this recovery
key is the only way a new device can ever decrypt old cloud archives. Losing
it does not weaken this device; it only means old cloud backups become
permanently unreadable if you ever need to restore from scratch elsewhere.
(This is a completely separate key from the app's own release-signing
keystore — see `docs/DECISIONS`.)

## Troubleshooting

- **Nothing happens / silently fails on a debug build** — expected: the
  Android OAuth client is registered against the *release* certificate's
  SHA-1 only. Install the release APK.
- **`ApiException` / authorization fails even on the release build** —
  double-check the package name and SHA-1 in Cloud Console match the release
  keystore exactly (`keytool -list -v` again to confirm), and that your
  Google account is listed under Test users (step 3.4).
- **Uploads stuck "in coda"** — check Impostazioni shows the account as
  connected; a revoked or lost connection is reported honestly rather than
  faking success, and the encrypted backup stays queued locally until you
  reconnect.
- **Revoking access**: `myaccount.google.com/permissions`, or the
  "Disconnetti Google Drive" button in JARVIS (best-effort revoke + always
  clears local state either way).
