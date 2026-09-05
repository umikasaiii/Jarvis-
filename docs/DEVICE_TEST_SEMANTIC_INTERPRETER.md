# Device test checklist — FASE 2A.9 Semantic Understanding Layer

Nessuna infrastruttura Robolectric/instrumented esiste in questo repository
(vedi `CLAUDE.md`), quindi il comportamento REALE del modello locale come
interprete semantico (`LocalLlmSemanticInterpreter`) non è certificabile da
questo ambiente — solo lo strato puro (`SemanticFrame`/`SemanticOutputParser`/
`SemanticFrameValidator`/`SemanticFrameMerger`) lo è, e lo è (`cd core &&
./gradlew test`). Questo file è la checklist minima da eseguire sull'HONOR 200
reale prima di dichiarare **DEVICE SEMANTIC ACCEPTANCE ✅**.

Per ogni turno, apri Diagnostica › pannello "Motore conversazionale (debug)"
subito dopo la risposta e annota: `semanticEnabled`, `semanticSource`,
`semanticIntent`, `semanticDomains`, `semanticOperation`, `explicitSlots`,
`inheritedSlots`, `currentOverridesPrevious`, `semanticValid`,
`semanticConfidence`, `semanticFailureReason`, `routingPath`,
`semanticLatencyMs`.

## Sequenza 1 — AGENDA, follow-up ellittici e range settimana

1. "Che impegni ho domani?"
   — atteso: `semanticSource=LOCAL_INTERPRETER` (o `LEGACY_FALLBACK` se
   l'interprete non è ancora disponibile), `semanticDomains=[AGENDA]`,
   `routingPath=SEMANTIC_CAPABILITY` (o `CAPABILITY_FAST_PATH` se il
   fallback ha risposto), risposta grounded sul vero `list_agenda`.
2. "E dopodomani?"
   — atteso: `semanticDomains=[AGENDA]` (`inheritedSlots` contiene `DOMAINS`),
   `explicitSlots` contiene `TEMPORAL_EXPRESSION`, risposta per dopodomani,
   NON per domani.
3. "E durante tutta la settimana prossima?"
   — atteso: `semanticDomains=[AGENDA]` (dominio ereditato), la risposta
   elenca impegni su un vero intervallo di 7 giorni (lunedì-domenica della
   settimana prossima), non un singolo giorno e non un rifiuto.

## Sequenza 2 — bleed di contesto HEALTH → WEATHER, poi media settimanale

1. "Quanto ho dormito questa settimana?"
   — atteso: `semanticDomains=[HEALTH]`, risposta reale da Health Connect
   (o l'errore onesto se i permessi mancano — mai un dato inventato).
2. "E la media?"
   — atteso: `semanticDomains=[HEALTH]` ereditato, `inheritedSlots` contiene
   `TEMPORAL_EXPRESSION` (la stessa settimana), aggregazione MEDIA non TOTALE.
3. "Domani farà caldo?"
   — **il test critico di questa intera fase**: atteso
   `semanticDomains=[WEATHER]`, `currentOverridesPrevious=true`,
   `explicitSlots` contiene `DOMAINS`. La risposta deve essere una vera
   previsione meteo di domani — MAI "Health Connect non ha dati per questo
   periodo" o qualunque risposta radicata in HEALTH.

## Sequenza 3 — KNOWLEDGE vs DEVICE_INFO, mai sostituire RAM↔VRAM

1. "Che differenza c'è tra RAM e VRAM?"
   — atteso: `semanticIntent=KNOWLEDGE_QUERY`, `semanticDomains=[KNOWLEDGE]`,
   nessun tool eseguito, risposta dal modello (qualità del modello FAST non
   certificabile da questo fix — vedi §9 del report).
2. "Quanta ne ho nel telefono?"
   — atteso: `semanticIntent=CAPABILITY_QUERY`, `semanticDomains=[DEVICE_INFO]`,
   metrica risolta a "ram" (mai "vram"), risposta con la RAM reale del
   dispositivo via `get_device_info`.
3. "E quanta ne ho libera?"
   — atteso: ancora DEVICE_INFO/ram, risposta coerente (spazio RAM libero se
   il tool lo espone, altrimenti onestà sul dato non disponibile).
4. "E la VRAM?"
   — atteso: **mai** una risposta che riporta il valore della RAM come se
   fosse VRAM — o un rifiuto onesto ("nessun dato VRAM affidabile"), o una
   risposta del modello che non inventa un numero.

## Sequenza 4 — paraphrase equivalence (nessuna keyword nuova)

Frasi mai inserite in un dizionario, in sequenza, ognuna deve instradare al
dominio corretto:
"Che tempo farà domani?" / "Mi conviene portare l'ombrello?" /
"Quanto ho dormito ieri?" / "Che impegni ho venerdì?"
— atteso: WEATHER, WEATHER, HEALTH, AGENDA rispettivamente, ognuna esplicita
(nessuna eredita dalla precedente per errore).

## Sequenza 5 — multi-source reasoning

"Considerando come ho dormito e gli impegni di domani, a che ora dovrei
andare a letto?"
— atteso: `semanticIntent=MULTI_SOURCE_REASONING`, `semanticDomains` contiene
sia HEALTH sia AGENDA, `routingPath=LLM_LOOP` (passa al ciclo di
ragionamento con grounding richiesto per ENTRAMBE le famiglie — mai una
risposta se uno dei due tool non è stato eseguito con successo).

## Onestà — limiti noti, non risolvibili da questo fix

- Il range "settimana scorsa" per HEALTH non è distinto dalla finestra
  mobile "questa settimana" esistente (`HealthQueryParser`/
  `HealthConnectManager` non supportano un ancoraggio a una settimana
  diversa da quella corrente) — il DOMINIO risolve correttamente via lo
  strato semantico, ma il RANGE per HEALTH resta quello preesistente.
  Vedi il report finale, punto 27.
- La qualità delle risposte KNOWLEDGE_QUERY dipende dal modello FAST/0.8B
  in uso — non è stata introdotta alcuna nuova fonte di verità (RAG/BRAIN)
  in questa fase, per istruzione esplicita.
- `semanticLatencyMs` misurato solo su device: se il modello classificatore
  dedicato non è importato, l'interprete condivide il motore FAST e può
  contendere il suo `chatMutex` con una generazione conversazionale
  concorrente (fino a 4s di attesa prima di un fallimento "busy").
