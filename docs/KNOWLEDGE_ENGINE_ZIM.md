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

---

# Knowledge Engine 2.0 — grounding rigoroso, ricerca e indice FTS (2026-11-14)

Stato: **CI VERIFIED** a run completata; **non DEVICE VERIFIED**. Le misure sotto sono su **fonti simulate** e **JVM**: non misurano Gemma né gli archivi reali.

## 1. Cause confermate (audit del percorso reale)

Caso segnalato: «capitale del Portogallo» ⇒ passaggi di Wikizionario classificati STRONG.

1. **Pertinenza scambiata per sufficienza.** `ZimTitleSearch` propone ogni sequenza di parole della domanda che sia il *titolo* di un articolo, anche una parola comune («capitale» è una voce di dizionario). `EvidenceAssembler` la marcava «named» e calcolava la copertura sui termini del passaggio **incluso il titolo stesso**: la voce «capitale» copre 1 termine su 2 col solo titolo, la voce «Portogallo» l'altro ⇒ ciascuna ≥ 0,34 ⇒ STRONG. Nessuna delle due dice quale sia la capitale.
2. **Il proper-noun gate era soddisfatto da qualunque menzione** (anche altrove nella pagina).
3. **La citazione era costruita da *tutti* i passaggi recuperati**, non da quelli che sostengono la risposta (`SourceFooter` + `SessionCoordinator`): una fonte non sufficiente compariva come «Fonte:».
4. **Limite di ricerca:** la ricerca per titolo è per *prefisso*: «Come si crea una variabile in Python» non trova l'articolo «Introduzione a Python», «Quando cadde Costantinopoli» non trova «Caduta di Costantinopoli», «quale organo produce l'insulina» non nomina «Pancreas». Servono contenuti indicizzati.
5. **Percorso documentale** (`DocumentImportManager.documentEvidence`): ha un proprio gate lessicale, ma la citazione è chiesta al modello («fra parentesi»), non costruita dai metadati. **Non modificato** in questo intervento (non è il caso segnalato); resta un limite dichiarato. Anche `search_knowledge` (tool) usa solo il gate lessicale sulla biblioteca testuale.

## 2. Correzioni

- `SupportJudge` (core): un passaggio è evidenza solo se una **finestra di 1–2 frasi** contiene i termini della domanda; i termini già nominati dal titolo/sezione dell'articolo sono impliciti. Tre gruppi di termini, ricavati dagli articoli recuperati (nessuna lista di parole): *propri* (il titolo li nomina), *altri soggetti* (titolo di un altro articolo), *attributi*. Senza attributo e con un altro soggetto candidato la **relazione** deve essere enunciata; una voce di **dizionario** (riconosciuta dai metadati dell'archivio: Name/Title/Tags, mai dal contenuto) deve contenere *tutti* i termini insieme. Domande di quantità richiedono un numero nella finestra. `Level`: SUPPORTED / RELATED (articolo sul soggetto ma attributo formulato diversamente: candidato, **mai prova**) / NONE.
- `Evidence.Insufficient` (nuovo): fonti rilevanti ma nessuna finestra di supporto ⇒ **non** passate al modello come risposta, **non** citate come supporto.
- `STRONG` solo se l'articolo è sul soggetto **e** la finestra copre tutti gli altri termini; sovrapposizione lessicale ⇒ al massimo MODERATE.
- `GroundingVerifier` (post-generazione, codice puro, **non** il LLM): `NO_EVIDENCE`, `INSUFFICIENT_EVIDENCE`, `SOURCE_FOUND` (esiste un passaggio di supporto ma valori/nomi della risposta non confermati, o solo passaggi correlati), `CLAIM_SUPPORTED` (ogni numero e nome della risposta compare in una finestra di supporto; per risposte senza valori: ≥ 60% delle parole di contenuto).
- `SourceFooter` per stato: «Fonte: …» **solo** per CLAIM_SUPPORTED e solo per i passaggi che contengono i valori della risposta; «Fonti consultate (non confermano la risposta): …»; «Fonti offline consultate, senza una risposta: …». Costruito dai metadati dei passaggi, mai dall'output del modello.
- `SessionCoordinator`: al modello arrivano solo le frasi di supporto; per `Insufficient` un avviso («nessuno contiene la risposta… dichiaralo»), nessun testo delle fonti.

## 3. Cosa il grounding NON garantisce (limiti residui)

- È **co-occorrenza lessicale**, non implicazione logica: una finestra può contenere le parole giuste accanto a un'affermazione sbagliata; negazioni e confronti non sono compresi.
- Nessun thesaurus: parafrasi con parole diverse («città principale» per «capitale») e flessioni verbali con radici diverse («scoprì»/«scoperto», «alto»/«altezza») non combaciano ⇒ degradano a *correlato/insufficiente*, mai a falso supporto; ma il recall ne soffre.
- `CLAIM_SUPPORTED` controlla numeri e nomi propri (maiuscole): un fatto espresso solo con parole minuscole non è verificato puntualmente.
- Domande booleane/di elenco su più entità («Il Vesuvio e le Alpi sono vulcani?», «cosa sono X e Y») sono trattate in modo conservativo.
- Due sensi dello stesso titolo («Mercurio»): il giudice lessicale può scegliere quello sbagliato (caso noto nel set di sviluppo: 1 su 53, MODERATE, mai STRONG); la verifica della risposta impedisce comunque il CLAIM_SUPPORTED se i valori non compaiono.
- Il secondo passaggio dello stesso LLM **non** è una verifica indipendente e non è usato.

## 4. Indice SQLite FTS persistente (B01 — realizzato, opt-in)

- `knowledge_fts.db` separato da `jarvis.db` (cache ricostruibile; nessuna migrazione di dati utente; cambio schema ⇒ svuota e ricostruisce la cache).
- FTS4 (`unicode61`) + `fts4aux`: ricerca per contenuto, riparazione degli errori di battitura contro il vocabolario dell'indice (≥ 6 lettere, distanza ≤ 1–2, stesse 2 lettere iniziali), AND dei termini poi rilassato togliendone 1–2; i candidati sono riordinati dallo **stesso** `KnowledgeSearch` e giudicati dallo **stesso** `EvidenceAssembler` (un solo knowledge store, nessun secondo orchestratore).
- **Selettivo:** si indicizzano la biblioteca testuale (md/txt, incrementale per dimensione+data, rimozione dei file spariti) e, **solo per gli archivi scelti dall'utente con «Indicizza»**, l'*introduzione* degli articoli (≤ 2 passaggi). Nessuna indicizzazione automatica o totale dei grandi ZIM; tetto articoli 250.000.
- **Limite di spazio configurabile** (50/150/300/600 MB, default 150): oltre, lo stato è *parziale* e quanto indicizzato resta utilizzabile.
- **Ripresa dopo interruzione:** ogni batch (40 articoli) e il suo cursore sono nella stessa transazione; un crash a metà batch non lascia nulla.
- **Aggiornamento/rimozione:** firma file diversa ⇒ l'indice di quella fonte è sostituito (non duplicato); «Elimina indice» e la rimozione di un archivio dall'Hub cancellano solo l'indice.
- **Memoria personale e documenti importati non entrano** nell'indice (restano nei loro percorsi).
- Cifre **JVM** (non del telefono): 30.000 passaggi ⇒ ~8 MB su disco, costruzione ~1,2 s, query ~7 ms.

## 5. Test e risultati (fonti simulate, JVM)

- `GroundingEvalTest`: **121 domande nuove**, 3 ambienti (telefono oggi = dizionario+medicina+manuali senza enciclopedia; con enciclopedia; conflitto vecchio/nuovo), metà *BLIND* valutata per prima.
  - Prima esecuzione BLIND, sola lettura per titolo: risposte supportabili 69,4% (25/36), non supportabili senza falso supporto 100% (21/21).
  - Dopo le correzioni decise **solo sul set DEV**: titoli 77,8% (28/36); **titoli + indice FTS 94,4% (34/36)**; non supportabili 100% (21/21) sul BLIND. Su tutto il set: falso supporto 1/53 (STRONG: 0), supporto esplicito stabilito 92,6% (50/54) con indice (81,5% senza), fonti discordanti segnalate 2/2.
  - Caso Portogallo: voci «capitale» e «Portogallo» recuperate (pertinenza) ⇒ `Insufficient`; con un articolo enciclopedico che lo afferma ⇒ supportato, citata solo la fonte che lo contiene.
- `KnowledgeEvalTest` (107 domande della fase precedente): 95,1%, 0 falsa evidenza.
- `GroundingVerifierTest`: stati, nomi/numeri inventati, passaggi solo correlati, citazioni scartate.
- `KnowledgeIndexTest`, `ZimIndexerTest`: contenuto vs titolo, refusi, incrementale, budget, ripresa, rollback su crash, ricostruzione schema, scala.
- Emulatore (workflow dedicato): `KnowledgeIndexInstrumentedTest` (SQLite/FTS4/fts4aux reali di Android) e `ZimModernArchiveInstrumentedTest`.
- **Nessuna accuratezza dell'AI reale è dichiarata**: serve il collaudo su Honor 200.

## 6. Cinque prove pratiche sull'Honor 200

1. **Portogallo, solo dizionario/Wikimed/Wikibooks:** «Qual è la capitale del Portogallo?». Atteso: nessun «Fonte:» di supporto; se risponde (Lisbona) la nota «non verificabile offline / fonti pertinenti non contengono una risposta» e «Fonti offline consultate, senza una risposta: Wikizionario…».
2. **Prova ricerca (Biblioteca offline):** stessa domanda ⇒ «Fonti pertinenti ma INSUFFICIENTI», con le voci elencate e i termini mancanti.
3. **Contenuto senza titolo:** «Indicizza» su Wikimed (limite 150 MB), poi «quale organo produce l'insulina?» ⇒ passaggio del Pancreas con la frase di supporto; verificare spazio occupato, avanzamento, **Pausa**/ripresa (chiudi l'app durante l'indicizzazione e riprendi) ed **Elimina indice**.
4. **Voce di dizionario legittima:** «Che cosa significa capitale?» ⇒ risposta con «Fonte: Wikizionario…».
5. **Risposta non confermata:** una domanda su un dato che compare nelle fonti solo in forma vicina (es. un valore diverso) ⇒ la risposta deve riportare «non è confermata dai passaggi trovati» e «Fonti consultate (non confermano la risposta)». Annota anche tempi e consumo percepiti durante l'indicizzazione.
