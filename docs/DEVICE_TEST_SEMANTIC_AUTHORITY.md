# Device test checklist — FASE 2A.10 Semantic Router Authoritative

Nessuna infrastruttura Robolectric/instrumented esiste in questo repository
(vedi `CLAUDE.md`), quindi il comportamento REALE del modello locale come
interprete semantico e del ciclo di ragionamento (`runBrainLoop`) non è
certificabile da questo ambiente — solo lo strato puro (`SemanticFrame`/
`SemanticFrameMerger`/`SemanticRouter`/`TemporalScopeResolver`/
`RelevantToolSelector`/`GroundingGate`) lo è, ed è verde (`cd core &&
./gradlew test`, 1003/1003 al momento di scrivere). Questo file è la
checklist minima da eseguire sull'HONOR 200 reale prima di dichiarare
**DEVICE SEMANTIC AUTHORITY ACCEPTANCE ✅** — FASE 2A.10 non è considerata
completa finché questa checklist non è stata eseguita e le sei sequenze
sotto non hanno dato l'esito atteso.

Per ogni turno, apri Diagnostica › pannello "Motore conversazionale (debug)"
subito dopo la risposta e annota tutte le righe, in particolare quelle
aggiunte da questa fase: `retry=…(ok=…)`, `legacyUsato=…`,
`legacyDopoSemanticaValida=…` (deve **sempre** leggere `false` — un `true`
qui è una regressione reale, non solo una nota) — oltre a quelle già
esistenti dalla fase precedente: `semantic=…`, `fonte=…`, `valido=…`,
`latenza=…`, `intent=…`, `domini=…`, `operazione=…`, `esito=…`, `path=…`,
`modelRounds=…`, `richieste=…`, `soddisfatte=…`.

## Sequenza 1 — Cross Domain (il bug capostipite di questa fase)

Riproduce esattamente il bug report che ha aperto FASE 2A.10, non una sua
riduzione a due frasi isolate.

1. "Quanto ho dormito questa settimana?"
   — atteso: `domini=HEALTH`, `esito=ANSWER` (o `HANDOFF_LLM` se il dominio
   HEALTH non rientra fra i quattro risolti direttamente — verificare che la
   risposta resti comunque grounded su Health Connect).
2. "E la media?"
   — atteso: `domini=HEALTH` ereditato, aggregazione MEDIA non TOTALE.
3. "Domani farà caldo?"
   — **il test critico**: atteso `domini=WEATHER`, mai HEALTH. La risposta
   deve essere una vera previsione meteo di domani — MAI "Health Connect
   non ha dati per questo periodo" o qualunque risposta radicata in HEALTH.
   `legacyUsato` deve leggere `false` per questo turno specifico (l'interprete
   ha davvero risolto WEATHER, non è caduto sul fallback).
4. "Che impegni ho domani?"
   — atteso: `domini=AGENDA`, mai WEATHER né HEALTH residuo.

## Sequenza 2 — Agenda Range (il gap genuinamente mancante prima di questa fase)

1. "Che impegni ho tra oggi e venerdì?"
   — atteso: `domini=AGENDA`, la risposta elenca impegni su un vero
   intervallo [oggi, venerdì] — non solo oggi, non un rifiuto "non capisco
   l'intervallo". Verificare che impegni di più giorni nell'intervallo
   compaiano tutti, non solo il primo.
2. "E da lunedì a giovedì?"
   — atteso: stesso meccanismo con la forma "da X a Y", intervallo
   [lunedì, giovedì] della settimana corrente o successiva a seconda di dove
   cade "oggi" — mai un singolo giorno.
3. "E questo weekend?"
   — atteso: intervallo sabato-domenica della settimana corrente.
4. "Quanti impegni ho in totale questa settimana?"
   — atteso: la risposta pronuncia un conteggio reale sull'intera settimana
   (lunedì-domenica), non solo "oggi".
5. Verificare la fluidità del linguaggio: nessuna risposta deve ripetere il
   giorno due volte nella stessa frase (es. "Hai un impegno per domani:
   domani alle 08:00, …" — il bug di fraseggio ripetitivo segnalato
   dall'utente). Un elenco di più impegni sullo stesso giorno singolo deve
   nominare il giorno una sola volta nell'introduzione.

## Sequenza 3 — Knowledge vs Device (nessuna sostituzione RAM↔VRAM)

1. "Che differenza c'è tra RAM e VRAM?"
   — atteso: `intent=KNOWLEDGE_QUERY`, nessun tool eseguito, risposta dal
   modello (qualità non certificabile da questo fix — vedi limiti sotto).
2. "Quanta ne ho nel telefono?"
   — atteso: `intent=CAPABILITY_QUERY`, `domini=DEVICE_INFO`, risposta con
   la RAM reale del dispositivo via `get_device_info` — mai VRAM.
3. "E la versione di Android?"
   — atteso: ancora DEVICE_INFO, risposta con la versione reale, non un
   fallback generico.

## Sequenza 4 — Multi Source (grounding genuino su più fonti)

1. "Considerando come ho dormito e gli impegni di domani, a che ora dovrei
   andare a letto stasera?"
   — atteso: `intent=MULTI_SOURCE_REASONING`, `domini` contiene sia HEALTH
   sia AGENDA, `esito=HANDOFF_LLM`, `path=LLM_LOOP`. La risposta finale deve
   riflettere ENTRAMBI i dati reali (un vero orario di sonno recente E un
   vero impegno di domani) — mai "Non riesco ad accedere a quel dato" se
   entrambi i tool erano davvero disponibili, e mai una risposta che ne cita
   uno solo. Se uno dei due dati manca genuinamente (es. Health Connect
   senza permessi), la risposta deve nominare ESATTAMENTE quale manca
   ("Non ho ancora i dati di salute: non posso rispondere con certezza"),
   mai un rifiuto generico.

## Sequenza 5 — Hard Command (il whitelist non deve mai allargarsi)

1. "Accendi la torcia"
   — atteso: risposta immediata (`fastPathHit=true` nel pannello base),
   nessuna chiamata all'interprete semantico per questo turno.
2. "Metti in pausa la musica"
   — atteso: stesso fast path immediato (`media_control`).
3. "Che impegni ho oggi?"
   — atteso: **NON** deve essere intercettata dal fast path — deve passare
   per l'interprete semantico (`semantic=true`) prima di rispondere, anche
   se la risposta arriva comunque rapidamente. Questo pinna il fix
   principale di FASE 2A.10: `FastPathRouter` non intercetta più le
   richieste in linguaggio naturale (agenda/meteo/salute), solo torcia e
   controllo media.
4. "Ricordami di comprare il latte"
   — atteso: passa per l'interprete semantico o per lo Structured Path
   AGENDA (post-semantico), mai per un fast path hardcoded.

## Sequenza 6 — Failure (nessun routing per associazioni quando l'interprete fallisce)

Difficile da forzare deterministicamente su device (nessun modo di
simulare un timeout dall'esterno), ma osservabile indirettamente:

1. Fai una domanda ambigua/rara più volte di seguito finché non si osserva
   `retry=true` nel pannello (l'interprete ha fallito almeno una volta).
   — atteso: se `retry=true(ok=false)` (anche il retry è fallito),
   `legacyUsato=true` e la risposta viene dal ciclo di ragionamento completo
   (`runBrainLoop`), MAI da un dominio indovinato per keyword/topic — in
   particolare, se il turno precedente era HEALTH e questo turno fallito
   contiene una parola vagamente temporale ("domani"), la risposta NON deve
   essere radicata in HEALTH.
2. Con `retry=true(ok=true)` (il retry ha avuto successo) — atteso: la
   risposta riflette la SECONDA interpretazione, non un misto delle due.
3. In ogni caso di `legacyUsato=true`, verificare `legacyDopoSemanticaValida`
   — deve **sempre** leggere `false`. Se mai leggesse `true`, è una
   regressione reale del canary di rilascio, da segnalare immediatamente:
   significherebbe che un frame semantico VALIDO è comunque finito nel
   percorso legacy, l'esatto bug che FASE 2A.9.1/2A.10 esistono per chiudere.

## Onestà — limiti noti, non risolvibili da questo fix

- Il range "settimana scorsa" per HEALTH non è distinto dalla finestra
  mobile "questa settimana" esistente (`HealthQueryParser`/
  `HealthConnectManager` non supportano un ancoraggio a una settimana
  diversa da quella corrente) — il DOMINIO risolve correttamente via lo
  strato semantico, ma il RANGE per HEALTH resta quello preesistente.
- La qualità delle risposte KNOWLEDGE_QUERY e la naturalezza del fraseggio
  generale dipendono dal modello FAST/0.8B in uso — nessuna nuova fonte di
  verità (RAG/BRAIN) è stata introdotta in questa fase, per istruzione
  esplicita dell'utente ("NON toccare BRAIN oltre il minimo indispensabile").
- Un endpoint "arbitrario" (es. "tra 12 giorni e 20 giorni" per l'agenda) non
  è stato specificamente testato: il resolver è generico sulla FORMA "tra X
  e Y"/"da X a Y" con endpoint a una singola parola (oggi/domani/dopodomani/
  un giorno della settimana) — un endpoint a due parole ("12 agosto") non è
  ancora supportato da questa forma di intervallo, limite dichiarato anche
  nel doc comment di `TemporalScopeResolver`.
- `semanticLatencyMs`/il tasso reale di retry sono misurabili solo su
  device: se il modello classificatore dedicato non è importato, l'interprete
  condivide il motore FAST e può contendere il suo `chatMutex` con una
  generazione conversazionale concorrente.
