# JARVIS Offline Hub — v1

This variant extends JARVIS Mobile for Android without changing its existing on-device LiteRT-LM engine or personal data.

## Intended behavior

- Import a compatible **.litertlm** instruction model in the existing **Models** screen. No model binary is shipped.
- New **Offline** bottom tab: file import (TXT, MD, HTML, CSV; max 4 MB per file), read-only document folder via SAF, and manual reindex.
- Imported documents reside in the private app directory offline_reference, **separate from the Obsidian personal memory and chat history**. The linked folder has only Android-granted read access.
- The existing source-labeled lexical retrieval is attached to the local AI conversation in SessionCoordinator.knowledgeEvidence(). The Offline tab queues a text question and opens the JARVIS chat.
- Organic Maps and Kiwix launch as **separately installed apps**. Install both, download map regions and ZIM archives while online first.
- Kiwix article excerpts can be copied and saved from the Offline tab; the app cannot currently parse entire ZIM archives.

## Test on HONOR 200

1. Install the debug APK; open Offline → Apri modelli AI; import a compatible .litertlm and load it.
2. In Offline → Importa un file, import a UTF-8 text document and check the document count.
3. Ask a question answered only in that file; check source citations, with airplane mode enabled.
4. Link a separate document folder and repeat the question; after editing, reindex.
5. In Kiwix, copy an article excerpt; paste and archive it inside Offline; ask JARVIS about it.
6. Open Organic Maps and navigate using pre-downloaded maps in airplane mode.
7. Restart JARVIS and verify that imported documents persist.
8. Verify imported reference content does not appear in personal memories.

## Not included yet

- Direct PDF/EPUB text extraction, complete ZIM/libzim search and semantic-embedding indexing.
- GGUF model import. The actual local inference uses LiteRT-LM .litertlm models.
- Full map control inside JARVIS: Organic Maps stays independent.
- Signed production release and HONOR 200 performance tests.

No Internet is needed for local queries after the model and sources are prepared. Device tests are needed before claiming a fully validated APK.
