# JARVIS GUARDIAN — G1 Architecture, Threat Model & G2 Initial Scope

**Status:** G1 architecture and security boundaries recorded; G2 implementation is to be validated in CI and on an Honor 200. This is a separate, non-publishing branch, not a replacement for the installed Beta. G3–G7 are planned, **not implemented**.

## Baseline audit / preservation
- Runtime base commit: `169cd4d` (BETA 8 Hotfix 3 LiteRT-LM stability). Do not undo it or conflate CI green with Honor-verified.
- Android: `minSdk 31`, `targetSdk 35`, `compileSdk 35`, Kotlin/Compose, Hilt, `JarvisApp` full-screen overlay navigation. Honor 200 (MagicOS/Android 16) is the priority device.
- Existing capabilities include private backup/restore, app diagnostics, memory, local model, document RAG, translator, navigation, Kiwix, automation and notifications. Reuse their authorities; Guardian is NOT a new vault/scheduler/AI router, root tool, remote controller, or monitor of arbitrary apps.
- Existing manifest has no `VpnService`; no current VPN packet processing. The only Guardian-specific permission in G2 is `android.permission.QUERY_ADVANCED_PROTECTION_MODE` (Android API 36+), scoped to a documented read-only system query. No VPN, Accessibility, all-files, package-query or device-admin privileges in G2.
- Do not change `latest-debug`, `beta-current`, the stable applicationId, the installed Beta, the Master 30-step denominator, or A1/A3 status.

## Product contract: four layers
| Layer | Target | Gate |
|---|---|---|
| Device Guardian | **G2 initial**: offline checks, local recommendations and user-selected file fingerprint | No root, OS settings remain under user control; every state grounded in an actual observation |
| Network Guardian | **G3 future**: single `VpnService` local packet pipeline with packet forwarding, firewall, DNS/domain lists, per-app restrictions where supported | Prior explicit VPN consent; no conflicts with other VPNs; tests for network loss/revocation, IPv6, DNS, battery, cellular/Wi-Fi, emergencies |
| Private VPN | **G4 future**: maintained WireGuard implementation incorporated into same VPN stack, with reconnection and fail-safe behavior | No proprietary crypto; keys safe; clear loss-of-connectivity and DNS/IPv6-leak semantics; always-on/lockdown only if truly supported |
| Private browsing | **G5 future**: intentional handoff to official Tor Browser | No embedded Tor browser, no blanket Tor mode for other apps; explicit limits on anonymity and fingerprinting |

**G6 planned:** independent/authorized security review, OWASP MASVS/MASTG, dependency/native audit, SAST, fuzzing and privacy checks. **G7 planned:** real device acceptance, including power/network/performance, emergency calls, Wi-Fi/mobile reconnection, boot/updates, MagicOS restrictions and offline regressions.

## Threat model — initial
**Assets:** device configuration, local notes and archives, secrets/keys, AI memory, VPN credentials, internet connectivity, location and availability of phone functions.
**Adversaries:** malicious apps, hostile files/APKs and webpages, phishing, compromised DNS/Wi-Fi, untrusted blocklists/update endpoints, prompt injection, local thief or opportunist with unlocked device, spoofed intents/components, supply-chain tampering.
**Capabilities of normal Android app:** reading own package and publicly exposed status, user-picked file streams, OS settings links; on Android 16+ read-only `AdvancedProtectionManager` if permission granted; VPN traffic only after explicit `VpnService` authorization and functioning packet pipeline. Cannot enumerate all installed apps or scan other app data by default; cannot guarantee bootloader state, verified boot attestation, SELinux configuration, device patch availability, malware absence, or global security posture.

## Permission and data flow (G2)
```text
User opens Guardian screen
  -> snapshot on IO dispatcher -> explicit checks -> localized UI per-check status
  -> selected Settings button -> Android OS-managed settings activity (user acts)
User selects a document using system picker
  -> ContentResolver stream read-only -> bounded SHA-256 -> hash/size returned to UI
  -> NO file persistence, upload, network, AI classification, APK install or execution
```
- No background services for G2. Snapshot runs on opening or explicit refresh, not a promised "always-on IDS." Real continuous protections remain those furnished by Android itself.
- Scoped check states: `PROTECTED` = **the measured requirement of this individual check** holds; `ATTENTION` = specific negative observation; `UNVERIFIED` = insufficient evidence; `UNAVAILABLE` = OS/API cannot expose. There is **no aggregate "phone protected"** without a complete proven signal set.
- Patch date is **reported by the OS**, freshness is advisory and must NOT be confused with checking available OTA updates. Secure screen lock is a direct `KeyguardManager.isDeviceSecure` observation, not a claim that theft is impossible. Android Advanced Protection is API 36+ and requires its query permission. `Build.VERSION.SDK_INT` is allowed to gate reflective API access while keeping compileSdk 35.
- File hashing proves byte fingerprint only, never absence of malware or trustworthiness. No UI name, file content, archive URLs or biometric data are written to persistent event logs. Hash is shown locally, never sent to telemetry.
- Beta debug signing is reported as a limitation; no release-grade integrity claim.
- Guardian cannot toggle Advanced Protection, bootloader lock, or privileged OS policies directly; it can open system settings with explicit user click.

## Guardrails and negative tests
- Unauthorized requests from model output, retrieved files, external intents or webpages cannot activate VPN, scans, settings or remote actions.
- G2 owns no exported Activity/receiver/service and introduces no persistent background traffic. It is accessed from Settings -> Guardian and separate app overlay.
- Do not mark VPN, WireGuard or Tor as active when no implementation exists. Keep future cards labeled `NON DISPONIBILE — previsto`.
- Verify old screens and features still compile; ensure no auto-publish job fires on `claude/guardian-g1-g2`. Test `GuardianPolicy`, file size limit, patch status, and non-deceptive Android check result mapping.

## G2 manual acceptance checklist (Honor 200)
1. Airplane mode and Wi-Fi off; open Settings -> Guardian, refresh; observe Android version, OS patch date and secure screen-lock state.
2. Android 16 Advanced Protection enabled/disabled -> state reflects OS (when API available and query permission granted); never infer enabled just from Android 16.
3. Tap "Apri impostazioni sicurezza": the **system** opens settings; no silent action, nothing is modified.
4. Choose a small test file, inspect locally, compare SHA-256 with independent hash; select >128 MiB and observe bounded, honest rejection; cancel picker without side effects.
5. Remove/disallow file URI permission and verify no scanning continues; no unexpected network traffic; no personal file path in diagnostics.
6. Confirm any incomplete/unavailable signal stays NON VERIFICATO / NON DISPONIBILE, not "protected"; VPN/WireGuard show inactive or not available.
7. Re-test local Gemma chat, memory, RAG, notes, translator and backup; Guardian must not degrade them. Benchmark app RAM and battery before/after as appropriate.

## Future milestones
- **G3 (blocked):** design one VPN datapath + user approval + packet parsing/forwarding + DNS/IPv6 + per-app handling. One active VPN per Android user/profile; if user uses another VPN, default to non-invasive guidance and no automatic replacement.
- **G4 (blocked):** integrate verified, maintained WireGuard engine in same approved VPN stack; no assumption that one Android profile can run two independent VPN services.
- **G5 (blocked):** Tor Browser official install/launch and education; no automatic browser session or covert tunnel.
- **G6 (blocked):** MASVS security testing; pen tests on owned/authorized devices only, native dependency auditing, CI release gates.
- **G7 (blocked):** Honor 200 real validation, no "9/10" claims without independent benchmarks or externally defensible methodology.

**Definition of Done (G1/G2 initial):** actual Android checks and file hashing accessible in app, no new sensitive privilege or network path, automated tests green, no Beta release; **device state remains PENDING** until the user tests on Honor 200. No promise of absolute protection or complete anonymity.
