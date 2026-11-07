# Room schemas (`jarvis.db`)

`JarvisDatabase` has `exportSchema = true`; KSP writes
`app/schemas/com.simone.jarvismobile.background.JarvisDatabase/<version>.json`
on every build. **Commit those files**: `JarvisMigrationInstrumentedTest`
(`MigrationTestHelper`) builds real pre-upgrade databases from them.

* A schema file is never edited by hand and never invented. The file for version N
  is whatever Room generated for the code at version N.
* Versions that predate this export (1–14) are recovered from the real code of the
  commit that last shipped each version: see `tools/db/export_historical_schemas.sh`
  and the manual `Room historical schemas` workflow. Until they are committed the
  instrumented test skips those versions explicitly (it does not pass silently).
* Status of each version is tracked in `docs/ANDROID_OFFLINE_STEP02_DATABASE_HARDENING.md`.
