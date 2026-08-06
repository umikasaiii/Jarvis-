# ADR 0007 — Structured Memory V2

## Decision

JARVIS keeps two separate local memory layers:

- conversation memory is a bounded structured recap in app-private storage and
  is deleted by **Nuova conversazione**;
- permanent and sensitive memories remain human-readable records in
  `JARVIS/Memoria.md` inside the user-selected Obsidian vault.

Each vault record has a stable ID and structured topics/people/dates in an
adjacent HTML comment. The visible Markdown bullet remains editable in Obsidian;
when its text changes, JARVIS preserves the ID/type and rebuilds the searchable
fields. Sensitive bullets display a lock marker.

## Safety rules

- Every voice/chat memory write requires an exact confirmation.
- Sensitive data is never silently promoted from conversation memory to the vault.
- Passwords, PINs, OTPs, tokens, API keys and seed phrases are rejected.
- Retrieval injects only a bounded relevant subset, never all memories.
- Obsidian is the source of truth; the in-memory index is rebuildable and is
  refreshed on app resume, before queued work, or manually from the Memory screen.

## Consequences

The deterministic recap avoids a second LLM generation and works offline. It is
less semantic than an embedding-based summary, but it is fast, inspectable and
cannot invent personal facts while writing memory.
