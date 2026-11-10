# Beta publishing — stable direct link

Every verified Beta build is published by the `publish-beta` job in `.github/workflows/ci.yml`.

- Direct link: `https://github.com/umikasaiii/Jarvis-/releases/download/beta-current/JARVIS_BETA.apk`
- Release `beta-current`: prerelease, never "Latest"; asset name fixed (`JARVIS_BETA.apk`).
- Historical, immutable releases `beta-<sha7>` (asset `JARVIS_BETA-<sha7>.apk`) allow rollback.
- Never touches `latest-debug` or the main applicationId (`com.simone.jarvismobile.debug.beta` only).

Gates (all on a GitHub runner, no rebuild): `needs: [build, isolated-apk]` (tests + isolated build green);
`push` events on `claude/beta*` branches of this repository only (no pull_request / forks);
`contents: write` only on this job; checksum, commit, applicationId, debug-cert SHA-256
(`33018d7f…c8fb`), apksigner verification and zip integrity checked before publishing;
a run whose `run-number` is not newer than the one recorded in the release notes leaves
`beta-current` untouched; the final step downloads the public link and compares SHA-256.
The release notes record commit, branch, CI run URL, run-number and SHA-256.
Guard: `.github/scripts/ci_publish_guard.py` rule R7 (mutation-tested by `test-ci-publish-guard.sh`).

## Documentation-only pushes
`publish-beta` also requires `needs.build.outputs.apk_impacting == 'true'` (same classifier as `latest-debug`,
`.github/scripts/classify-apk-impact.sh`). Pushes touching only `docs/`, `CLAUDE.md`, `README.md`, the CI
workflow/guard scripts do not publish a Beta; `beta-current` keeps pointing to the last verified build.
