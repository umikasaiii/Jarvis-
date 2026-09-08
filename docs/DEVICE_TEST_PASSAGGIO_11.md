# Device test checklist — PASSAGGIO 11 Stabilization Acceptance Checkpoint

Questo file è il pacchetto di accettazione dispositivo per il checkpoint di
chiusura PASSAGGIO 11 — **non introduce nuove funzioni**, verifica che i
componenti stabilizzati dai PASSAGGI 1→10.3 lavorino insieme in modo coerente
sull'app reale, non solo ognuno isolatamente nei propri test `:core`/JVM.
Nessuna infrastruttura Robolectric/instrumented esiste in questo repository
(vedi `CLAUDE.md`), quindi tutto ciò che segue va eseguito sull'HONOR 200
reale (o un dispositivo/emulatore equivalente) con lo stesso build APK
identificato dalla SHA registrata sotto.

**Stato atteso legittimo**: `AUTOMATED=PASS, CI=PASS, DEVICE=PENDING` per
ogni riga finché questa checklist non è stata eseguita — non è un fallimento,
è lo stato onesto di un checkpoint la cui parte automatizzata è già chiusa.

## Identità build

- Commit HEAD al momento della scrittura: vedi `Commit SHA` nel report OUTPUT
  di PASSAGGIO 11 in questa stessa conversazione.
- `BuildConfig.BUILD_ID` (mostrato in Diagnostica › card "Ultima risposta
  chat", § fase 6n-audit) deve combaciare con la SHA a 7 caratteri di quel
  commit prima di iniziare — se non combacia, il device sta eseguendo una
  build diversa e nessun risultato di questa checklist è attribuibile a
  PASSAGGIO 11.

## Come annotare ogni passo

Per ogni scenario sotto: apri Diagnostica prima e dopo, annota i pannelli
pertinenti già esistenti (Motore conversazionale, Meteo, Salute, Proattività,
Ultima risposta chat) — non serve una nuova UI, i pannelli aggiunti nei
PASSAGGI precedenti bastano. Segna PASS/FAIL/PENDING per ogni riga.

---

## GRUPPO A — Session / Reset (C3, PASSAGGIO 3/MP-3)

1. Avvia una conversazione, fai scattare una disambiguazione agenda ("cancella
   l'appuntamento" con più candidati) — NON rispondere.
2. Tocca "Nuova conversazione".
3. Scrivi un messaggio scollegato ("Ciao").
   — atteso: risposta normale, MAI una risposta come se stessi ancora
   rispondendo alla disambiguazione di prima (la vecchia `pendingConfirmation`/
   `pendingDisambiguation` deve essere invalidata dal reset).
4. Ripeti con una conferma di scrittura in sospeso (es. "ricordami di
   comprare il pane" → "Confermi?") invece di una disambiguazione.

## GRUPPO B — Agenda (C4, PASSAGGIO 4/MP-4)

5. Aggiungi un impegno da Home (Attività).
6. Chiedi in chat "Che impegni ho oggi?" — atteso: l'impegno appena aggiunto
   compare immediatamente (nessuna cache stantia).
7. Elimina lo stesso impegno via chat ("cancella l'impegno di [nome]").
8. Torna alla Home — l'impegno non deve più comparire (stesso storage,
   nessuna divergenza Home/Chat).
9. Chiedi "Che impegni ho tra oggi e venerdì?" — un vero range multi-giorno.

## GRUPPO C — Health (C5, PASSAGGIO 5/MP-5)

10. Con Health Connect concesso: "Quante ore ho dormito questa settimana?"
    — atteso: risposta reale o "nessun dato per questa settimana" onesto,
    mai un valore inventato.
11. "Quante ore ho dormito stanotte?" — deve essere un valore DISTINTO dalla
    media settimanale (§ HealthQueryParser/HealthQueryParser generalizzato).
12. Revoca il permesso Health Connect da Impostazioni di sistema, ripeti
    passo 10 — atteso: messaggio "permesso mancante" onesto, mai un dato
    stantio presentato come fresco.

## GRUPPO D — Weather (C6, PASSAGGIO 7/MP-7)

13. Con un luogo "casa" salvato: "Che tempo fa oggi?"
14. Cambia il luogo Meteo in Impostazioni a un luogo diverso, richiedi di
    nuovo "Che tempo fa oggi?" — atteso: la risposta cambia in modo coerente
    con la nuova posizione (mai la cache del luogo precedente, § JARVIS-06
    `WeatherLocationKey`).
15. "Farà caldo domani?" — atteso: risposta con banda qualitativa (caldo/
    fresco/ecc.) supportata da una temperatura numerica reale nello stesso
    turno, mai un giudizio senza numero verificabile.
16. "Che tempo farà tra 10 giorni?" — deve essere una vera risposta (§
    orizzonte esteso PASSAGGIO 2A.8), non più il rifiuto "solo 3 giorni".

## GRUPPO E — Automazioni (C7, PASSAGGIO 8/8.1/MP-8/MP-8.1)

17. Crea una regola oraria (es. "ogni giorno alle 8 avvisami").
18. Forza lo scatto (attendi l'orario o usa un test manuale se disponibile in
    Diagnostica) — verifica che scatti UNA sola volta.
19. Uccidi il processo dell'app (kill da sistema, non solo swipe-away) subito
    dopo uno scatto, riavvia — verifica che lo stesso scatto non si ripeta
    (§ deduplica durabile MP-8/MP-8.1).
20. Verifica in Diagnostica/log che un tentativo con azioni parzialmente
    fallite non venga marcato come "già eseguita con successo" in modo da
    bloccare un retry legittimo.

## GRUPPO F — Navigation (C8, PASSAGGIO 9/9.1/MP-9/MP-9.1)

21. Avvia una navigazione verso una destinazione A.
22. Prima che il calcolo termini, cambia rapidamente destinazione a B.
    — atteso: la route mostrata è SEMPRE quella di B, mai un flash della
    route di A arrivata in ritardo.
23. Avvia una navigazione, tocca "Stop" quasi subito dopo (mentre il calcolo
    è ancora in corso).
    — atteso: nessuna route "resuscita" dopo lo stop.
24. Avvia una nuova navigazione dopo lo stop del passo 23 — deve funzionare
    normalmente (generazione nuova, non bloccata da quella precedente).
25. Durante una navigazione attiva, verifica che un reroute fallito non
    cancelli la route corrente valida (resta visibile, solo un messaggio di
    errore transitorio).

## GRUPPO G — Backup/Restore (C9, PASSAGGIO 10/10.1/10.2/10.3/MP-10.x)

26. Esegui un backup manuale ("Esegui backup ora").
27. Modifica dati reali (aggiungi un impegno, una nota).
28. Ripristina dal backup del passo 26.
29. Riavvia l'app completamente (kill + riapri, non solo torna in
    background).
30. Verifica: i dati canonici (regole, agenda, memoria, archivio) sono quelli
    del backup, non quelli modificati al passo 27.
31. Verifica: nessuna coda di risposta assistente (`assistant_tasks`) resta
    bloccata in uno stato "in corso" fantasma dopo il ripristino (§
    sanitizzazione post-cutover, MP-10.3).
32. Verifica: le cache derivate (previsione meteo, medie salute) NON sono
    presentate come dati freschi subito dopo il ripristino — devono
    rigenerarsi al prossimo refresh reale, non restare quelle del backup.
33. Interrompi forzatamente l'app mentre un ripristino è in corso (se
    riproducibile) — al riavvio successivo l'app deve completare la recovery
    prima di avviare qualunque altro scheduler (§ startup fail-safe barrier,
    MP-10.2), mai uno stato mezzo-vecchio mezzo-nuovo.

## GRUPPO H — Core-off / Foundation baseline (C1/C2, verifica trasversale)

34. Con "Abilita Core" spento in Impostazioni: verifica che ogni scenario dei
    gruppi B-G sopra funzioni identico a prima di questo checkpoint (nessuna
    regressione locale introdotta dal lavoro Core/Semantic di questa
    sessione).
35. Fai una domanda che il modello locale potrebbe non seguire correttamente
    nel protocollo (es. una frase ambigua) — verifica che nessun JSON grezzo
    del protocollo interno compaia mai in chat (§ PresentationGuard,
    PASSAGGIO 2/MP-2).
36. Fai una domanda che richiede un dato non disponibile (es. Health Connect
    disattivato) — verifica che la risposta sia un rifiuto onesto
    fail-closed, mai un dato inventato (§ GroundingGate, PASSAGGIO 1-2).
37. Verifica il pannello Diagnostica "Motore conversazionale (debug)" mostra
    `legacyDopoSemanticaValida=false` per ogni turno di questa sessione di
    test (canary di rilascio, non deve mai leggere `true`).
38. Apri Diagnostica → nuovo pannello (se esposto) o log per il Foundation
    Checkpoint Snapshot: verifica che compaia build id, ultimo turno motore,
    ultima diagnostica navigazione, ultima diagnostica restore, ultima
    occorrenza automazione, stato Core — tutti popolati dopo aver eseguito
    almeno un'azione per dominio (nessun campo perennemente null se l'azione
    corrispondente è stata eseguita in questa sessione).

---

## Riferimenti agli altri device-test doc di questa sessione

Ogni componente individuale ha già la propria checklist dedicata, non
duplicata qui salvo il minimo necessario per la verifica di INTEGRAZIONE:

- `docs/DEVICE_TEST_SEMANTIC_AUTHORITY.md` — FASE 2A.10, sei sequenze.
- `docs/DEVICE_TEST_EMBEDDING_SEMANTICS.md` — FASE 2A.11, classificatore
  semantico dedicato.
- `docs/DEVICE_TEST_SEMANTIC_INTERPRETER.md` — FASE 2A.9.
- `docs/DEVICE_TEST_HONOR_200.md` — checklist storica multi-fase, incluso
  l'audit FASE 2A.7.
- `docs/DEVICE_TEST_PHASES_4_6E.md` — fasi 4→6e (audio/TTS/reminder).

Questo file copre esclusivamente le SEAM fra i domini stabilizzati dai
PASSAGGI 1-10.3 del Master Plan, non ridimostra la logica interna di ciascun
dominio (già coperta da quei documenti e dai rispettivi test `:core`).
