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

**An "Android" OAuth client is tied to one exact (package name, signing
certificate SHA-1) pair.** Debug and release builds have different package
names (`com.simone.jarvismobile.debug` vs `com.simone.jarvismobile`,
via `applicationIdSuffix`) and different signing certificates, so each needs
its **own** OAuth Android client in Cloud Console. Both can exist side by
side under the same project/consent screen — the app code is identical
either way; Play Services just resolves whichever client matches the
package+certificate of the APK that is actually running.

**Currently testing with the DEBUG build.** JARVIS's debug signing is a
fixed, committed keystore (`app/debug.keystore`, tracked in git, same
`androiddebugkey`/`android`/`android` on every machine and every CI run —
see `docs/DEBUG_SIGNING.md`), so its SHA-1 is stable across rebuilds; you
only need to register it once. Steps 1–3 below are one-time and identical
regardless of which build you register; step 4 shows both the debug and
release variants — use debug for now.

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
3. **Package name / SHA-1** — create one client for whichever build you are
   about to test:

   | Build | Package name | SHA-1 source |
   |-------|--------------|--------------|
   | **Debug (current)** | `com.simone.jarvismobile.debug` | `app/debug.keystore` — `B9:A7:FA:01:2E:4A:1F:04:DD:97:BD:0A:55:4D:99:B2:59:E9:DC:41` (stable, see `docs/DEBUG_SIGNING.md`) |
   | Release (later) | `com.simone.jarvismobile` | your `jarvis-release.jks`: `keytool -list -v -keystore jarvis-release.jks -alias jarvis-release` |

   You can create both now or just the debug one — they don't conflict, and
   nothing in the app code changes between them.
4. Click **Create**. No client secret is issued for this type — Play Services
   resolves the correct project from the running APK's package name +
   certificate, nothing to paste into JARVIS.

## 5. Connect JARVIS

1. Install the **debug** APK built with `app/debug.keystore` (the CI artifact
   or a local `:app:assembleDebug` — no special setup needed, this is JARVIS's
   normal debug build). A build signed with a certificate that has no matching
   OAuth client in Cloud Console (e.g. someone else's ad-hoc debug keystore)
   cannot complete this — only `app/debug.keystore`, the one committed in this
   repo, matches what you registered in step 4.
2. Open JARVIS › Impostazioni › Backup e sincronizzazione.
3. Turn on **Sincronizza sul cloud**, pick **Google Drive** as the provider.
4. Tap **Collega Google Drive**. If Play Services already has consent cached
   this completes instantly with no UI; otherwise Google's own account/consent
   screen opens, you approve it, and you're back in JARVIS.
5. Impostazioni shows the connected account's email once it completes.

Later, moving to the release build only requires registering the release
row of the table above as a second OAuth client — the connection itself
(account, backup folder, archives) is Google-Drive-side state, not tied to
which JARVIS variant uploaded it; see `docs/DECISIONS/0015-drive-authorization-client.md`
for why the backup format itself never depends on which build created it.

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

- **`ApiException` / authorization fails** — double-check the package name
  and SHA-1 registered in Cloud Console match the build you actually
  installed (`keytool -list -v -keystore app/debug.keystore` for debug,
  storepass/keypass `android`, alias `androiddebugkey` — re-check with
  `keytool -list -v -keystore jarvis-release.jks -alias jarvis-release` for
  release), and that your Google account is listed under Test users (step 3.4).
- **Works on debug but nothing happens after switching to a release
  build (or vice versa)** — expected if you only registered one OAuth
  client. Each (package name, certificate) pair needs its own entry from
  step 4's table; the debug and release clients don't share registration.
- **Uploads stuck "in coda"** — check Impostazioni shows the account as
  connected; a revoked or lost connection is reported honestly rather than
  faking success, and the encrypted backup stays queued locally until you
  reconnect.
- **Revoking access**: the "Disconnetti Google Drive" button in JARVIS always
  clears the local connection state, but only Google itself can revoke the
  grant server-side — do that at `myaccount.google.com/permissions` if you
  want the account to stop recognizing JARVIS entirely, not just locally.
