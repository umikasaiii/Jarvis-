# Google Drive backup — Google Cloud Console setup

Google Drive sync for JARVIS's backups (Impostazioni › Backup e sincronizzazione
› Google Drive) is entirely optional and off by default — the whole app,
including local backup, works with zero Google account. This is only needed if
you want an encrypted copy of your JARVIS backups in your own Google Drive.

JARVIS ships with **no Google credential of any kind**. You create your own
Google Cloud project and paste its OAuth client id (and secret, if Google
issues one) into the app; they are stored on-device only, in
`EncryptedSharedPreferences`, never in this repository, never in the APK. See
`docs/DECISIONS/0014-drive-oauth-without-play-services.md` for why this uses a
plain OAuth 2.0 browser flow instead of Google Sign-In (this project stays
GMS/Play-Services-free everywhere, including here).

## 1. Create a Google Cloud project

1. Go to [console.cloud.google.com](https://console.cloud.google.com) and sign
   in with the Google account whose Drive you want JARVIS to back up to (or
   any account — the OAuth screen lets you pick the account at connect time).
2. Create a new project (any name, e.g. "JARVIS Backup").

## 2. Enable the Drive API

1. In the project, go to **APIs & Services › Library**.
2. Search for **Google Drive API** and click **Enable**.

## 3. Configure the OAuth consent screen

1. **APIs & Services › OAuth consent screen**.
2. User type: **External** (unless you have a Google Workspace org and want
   **Internal**). App name: anything (e.g. "JARVIS"). Support email: yours.
3. **Scopes**: add `https://www.googleapis.com/auth/drive.appdata` and
   `.../auth/userinfo.email`. `drive.appdata` is Google's own scope for
   per-app hidden data — it cannot see or touch your visible Drive files, and
   Google classifies it as non-sensitive, so it does not require an app
   verification review for personal/testing use.
4. **Test users** (while the app is in "Testing" publishing status, which is
   fine indefinitely for personal use): add your own Google account's email.
   Without this, Google will refuse to authorize it.

## 4. Create the OAuth Client ID

1. **APIs & Services › Credentials › Create Credentials › OAuth client ID**.
2. Application type: **Desktop app** — **not** "Android". This is the one step
   people get wrong: "Android" client IDs are tied to a package name + SHA-1
   fingerprint and are meant for the Play-Services Google Sign-In SDK, which
   this app deliberately does not use. "Desktop app" clients support the
   plain browser-redirect flow JARVIS actually uses (RFC 8252), and Google
   does not restrict them by package/signature at all.
3. Name it anything. Click **Create**.
4. Google shows a **Client ID** and a **Client secret**. Copy both.

You do not need to register a redirect URI in the console for a Desktop-app
client — Google accepts the custom-scheme redirect JARVIS sends
(`com.simone.jarvismobile:/oauth2redirect`) without pre-registration for this
client type.

## 5. Connect JARVIS

1. Open JARVIS › Impostazioni › Backup e sincronizzazione.
2. Turn on **Sincronizza sul cloud**, pick **Google Drive** as the provider.
3. Paste the **Client ID** (and the **Client secret** — Google's token
   endpoint currently expects it for Desktop-app clients even with PKCE; if a
   future Google change makes it optional, leaving it blank still works).
4. Tap **Salva credenziali**, then **Collega Google Drive**. Your browser
   opens Google's consent page; approve it, and you're returned to JARVIS.
5. Impostazioni shows the connected account's email once the round trip
   completes.

## 6. Before you ever need it: save the recovery key

Backups are encrypted with a key that lives only on this phone. In the same
screen, under **Chiave di recupero**, tap **Mostra la chiave di recupero** and
save the string somewhere durable (password manager, printed and stored
safely). You will need it to read your cloud backups from a **different**
phone — Android Keystore keys cannot be exported, by design, so this recovery
key is the only way a new device can ever decrypt old cloud archives. Losing
it does not weaken this device; it only means old cloud backups become
permanently unreadable if you ever need to restore from scratch elsewhere.

## Troubleshooting

- **"Autorizzazione negata"** — you declined consent, or your account is not
  listed under Test users while the app is unpublished (step 3.4).
- **"Google non ha restituito un refresh token"** — Google only issues a
  refresh token on the *first* consent for a given account+scope combination.
  If you disconnected and reconnected without a real revoke, Google may skip
  it. Revoke JARVIS's access first at
  [myaccount.google.com/permissions](https://myaccount.google.com/permissions),
  then reconnect.
- **Uploads stuck "in coda"** — check Impostazioni shows the account as
  connected; a revoked or expired connection is reported honestly rather than
  faking success, and the encrypted backup stays queued locally until you
  reconnect.
