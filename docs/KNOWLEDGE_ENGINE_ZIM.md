# Knowledge Engine — lettura diretta dei ZIM (Kiwix) e risposte ancorate

Stato: **CI VERIFIED** a run completata; **non DEVICE VERIFIED** finché la procedura sull'Honor 200 (in fondo) non è stata eseguita.
L'indice SQLite FTS persistente è una fase **separata e non ancora realizzata**: qui si cerca per **titolo** direttamente nel ZIM.

## Architettura (nessun nuovo orchestratore)

- `core/knowledge/zim/ZimArchive.kt` — lettore OpenZIM in puro Kotlin: header, MIME list, URL pointer list, cluster, dirent, redirect; accesso casuale tramite `ByteSource` (letture posizionali, mai l'archivio in RAM), cache limitata di cluster, tetti di decompressione.
- `ZimTitleSearch` (articoli per titolo), `ZimHtml` (HTML → testo a sezioni), `ZimKnowledgeSource` (→ `KnowledgeChunk` con fonte/titolo/sezione/data/riferimento).
- `EvidenceAssembler` → `KnowledgeRepository.gather` → `SessionCoordinator` (stesso percorso della conoscenza locale): risposta solo se l'evidenza copre la domanda; altrimenti «non verificabile offline». `SourceFooter` cita solo passaggi realmente recuperati.
- App: `SafByteSource` (file descriptor SAF + `FileChannel`), `AndroidZimDecompressor` (zstd-jni, xz-java), `ZimKnowledge` (verifica «Verifica lettura» per archivio, memorizzata con la dimensione del file).
- Licenza: libzim/libkiwix sono GPLv3, il progetto è Apache-2.0 ⇒ lettore proprio + zstd-jni (BSD-2) + xz-java (pubblico dominio). Vedi `THIRD_PARTY_NOTICES.md`.

## Compatibilità ZIM (ZIM HOTFIX, 2026-11-14)

**Causa confermata** (sorgente libzim `fileheader.cpp`/`fileimpl.cpp` + file reali): `titlePtrPos` (header, offset 40) è il puntatore della lista titoli **storica (v0)**; i writer moderni possono non scriverla e mettono il valore sentinella `0xFFFFFFFFFFFFFFFF` (`Fileheader::hasTitleListingV0()`), ordinando i titoli con la voce `X/listing/titleOrdered/v1`. Il lettore precedente pretendeva sempre la lista storica dentro il file ⇒ `pointer_lists_out_of_bounds` sui ZIM 2026.

Comportamento ora:

| Caso | Esito |
|---|---|
| `titlePtrPos` valido + nessun listing v1 (ZIM v5/6 storici) | indice `LEGACY_V0` |
| listing `X/listing/titleOrdered/v1` presente (con o senza lista storica) | indice `LISTING_V1` (preferito, come libzim) |
| `titlePtrPos` = sentinella e v1 presente | `LISTING_V1` |
| sentinella e **nessun** v1 utilizzabile | rifiuto onesto `title_index_missing` |
| `titlePtrPos` ≠ sentinella ma fuori dal file | ancora `pointer_lists_out_of_bounds` (controllo **non** indebolito) |
| listing v1 in cluster compresso / non allineato / indici fuori range | non usato (come libzim); senza lista storica ⇒ `title_index_missing` |

Il listing v1 è un array di `u32` (indici di voce, ordinati per titolo) dentro un cluster **non compresso**: si leggono solo il byte di info del cluster, due offset e, per la ricerca binaria, 4 byte per passo. Il v1 elenca **solo articoli veri (niente redirect)**: i titoli-redirect («USA») si raggiungono con una sonda esatta sull'indice URL (limitata a frasi ≤ 4 parole, 3 varianti di maiuscole).

Versioni: ZIM major 5 e 6 (minor 0–2 verificati); namespace `C` (nuovo) e `A` (storico); cluster non compressi, xz (4), zstd (5); offset di blob a 4 o 8 byte.
Limiti dichiarati: ricerca solo per **titolo** (l'indice Xapian `X/fulltext` richiede libxapian: non letto); errori di battitura sulle prime lettere / parole < 6 lettere non recuperati; nessun contenuto non testuale (immagini); i riepiloghi «mini» contengono solo l'introduzione degli articoli.

## Diagnostica (senza dati personali)

`ZimArchive.diagnostics()` / `ZimFormatException.diagnostics`: `zim=v6.1 entries=… clusters=… titlePtr=absent|present titleIndex=LISTING_V1|LEGACY_V0 titleCount=… mimes=…` (+ flag di validità dei puntatori in caso di errore). Mai nome file, percorso, titoli o testo. Mostrata sotto il report di «Verifica lettura» e scritta nel log tecnico.

## Test

- JVM `:core` (`ZimModernHeaderTest`, `ZimArchiveTest`, `RealZimEvalTest`): layout storico / moderno / entrambi / assente / listing compresso / puntatore invalido; equivalenza v1 ↔ storico; redirect; letture limitate su archivio grande moderno; 3 file ZIM reali del test-suite openzim (moderno con listing v1, moderno con header patchato alla sentinella, storici xz). 
- Android (emulatore, workflow `db-migration-tests`): `ZimModernArchiveInstrumentedTest` — `SafByteSource` + zstd-jni/xz reali su fixture sintetiche moderne e storiche.
- **Nessun file Wikipedia/Wiktionary/Wikibooks 2026 reale è stato aperto in sandbox** (host Kiwix non raggiungibile): il caso «sentinella» è provato su ZIM reali con header patchato e su sintetici, non su un file 2026 nativo.

## Procedura sull'Honor 200 (archivi già presenti)

1. Installa la Beta (`beta-current`). Biblioteca offline → gli archivi restano elencati (permessi persistenti).
2. Per ciascuno (`wikipedia_it_all_nopic_2026-05`, `wiktionary_it_all_nopic_2026-07`, `wikibooks_it_all_nopic_2026-07`) premi **Verifica lettura**.
3. Esito atteso: «Lettura diretta VERIFICATA», titolo/data/lingua/n. voci e, sotto, la riga diagnostica con `titlePtr=absent titleIndex=LISTING_V1` (o `present … LEGACY_V0`).
4. «Prova ricerca»: una domanda con un nome noto (es. una capitale), una con un nome assente, una con un redirect (sigla). Atteso: evidenza con fonte, oppure «nessuna evidenza».
5. In caso di fallo, annota **codice + riga diagnostica** (non contengono dati personali) e riferiscili.
