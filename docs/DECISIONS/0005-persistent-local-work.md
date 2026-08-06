# 0005 — Persistent local response work

## Decision

Typed requests are records in Room and are executed by unique WorkManager jobs.
Long inference is promoted to a visible `dataSync` foreground service; microphone
capture remains outside this worker and is never hidden in the background.

The worker restores the transcript, model and memory index before generation.
Every chat line carries an optional task ID, making retries idempotent after a
process death. Actions that require confirmation remain pending and are not
silently approved by background execution.

## Consequences

- answers continue when the Activity closes or the screen turns off;
- task state, retry and cancellation are observable in the app;
- Android may still delay the start under power restrictions, but cannot lose
  the queued request;
- response previews stay disabled by default for lock-screen privacy.
