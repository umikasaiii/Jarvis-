# Local knowledge: wiki and guides

JARVIS needs two stores with different responsibilities:

1. **Personal memory (Obsidian)** — facts Simone explicitly asks JARVIS to
   remember, agenda entries, and normal Markdown notes.
2. **Reference knowledge** — Wikipedia, manuals and downloaded guides. These are
   evidence sources, not personal memories, and must never be copied wholesale
   into `JARVIS/Memoria.md`.

## Target offline pipeline

`question → intent/context → local search → selected passages + source → LLM → answer`

The search layer runs before generation. The model receives only a small number
of relevant passages and their source identifiers. If the index has no adequate
evidence for a factual answer, JARVIS says so instead of filling the gap with an
invented detail.

## Formats

- Markdown and plain-text guides can reuse SAF folder access and chunking.
- PDF/EPUB require text extraction during import, with page/chapter metadata.
- A full Wikipedia dump should stay in **ZIM** format and be queried through a
  dedicated ZIM reader/index (for example a vetted libzim/Kiwix integration).
  Expanding Wikipedia into millions of Obsidian files would waste storage and
  make vault indexing impractical.

## Retrieval

The first useful implementation should combine local full-text search (FTS/BM25)
with a small semantic embedding model. Lexical-only overlap is insufficient for
questions whose wording differs from the guide. Embeddings are a rebuildable
cache; source files remain authoritative.

Every returned chunk carries:

- source title and stable local identifier;
- section/page when available;
- passage text;
- retrieval score and content hash.

The response layer should be able to show the sources used in the written chat,
while voice output stays concise.

## Resource limits

Indexing runs incrementally and off the main thread. The phone index stores text,
metadata and embeddings—not model-generated summaries of the whole corpus. A
bounded top-k context prevents large manuals or Wikipedia results from exhausting
the LLM context window.

## Acceptance tests

- Airplane mode: a guide-backed question returns the correct passage and source.
- A paraphrased question retrieves the same relevant section.
- No matching local source: JARVIS explicitly reports that it cannot verify the
  answer locally.
- Personal memories never appear as wiki sources, and wiki text is never written
  into `Memoria.md`.
