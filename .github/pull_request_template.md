## Summary

<!-- What changed and why. Link the phase (docs/IMPLEMENTATION_PLAN.md). -->

## Phase / scope

- Phase:
- Touches: `core/` / `app/` / `server/` / `docs/`

## Checklist

- [ ] `cd core && ./gradlew test` passes (if `core/` changed)
- [ ] `./gradlew :app:assembleDebug` builds (if `app/` changed; needs SDK)
- [ ] `./gradlew :app:lintDebug` clean (if `app/` changed)
- [ ] Docs / `CLAUDE.md` phase state updated
- [ ] No secrets, tokens, models, or personal audio added
- [ ] No new always-on mic; any background mic stays behind a foreground service
- [ ] Offline path still works without network

## Notes for reviewers
