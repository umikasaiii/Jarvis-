# JARVIS Beta finale — Piano di prova sull'Honor 200

**APK da installare (e solo questo):** build del commit `900c0e3de83f8a8ae7e360bde2bcb887f43cd5ba`, artifact `app-debug-isolated` del run https://github.com/umikasaiii/Jarvis-/actions/runs/42619188240 (scade il 2027-02-07).
Application ID `com.simone.jarvismobile.debug.beta`. **SHA-256 dell'APK:** `736f53ad90298b8e823452823177282cd10c53f200e5bed1614d7e7e4bb49248`. (Lo ZIP dell'artifact ha un altro hash: `bd870e1d…b1bc`; non confonderli.)
Dalla schermata/impostazioni dell'app deve comparire l'ID build **`900c0e3`**.

## Regole d'oro
1. **Non toccare l'app principale** (`com.simone.jarvismobile…` senza `.beta`). Non disinstallarla, non aggiornarla, non ripristinare backup su di lei.
2. Non disinstallare l'APK isolato fra una prova e l'altra: i suoi dati servono alle prove B e C. Installare **sopra** quello esistente, se c'è.
3. Dati **fittizi** nell'APK isolato. Non importarci il backup reale.
4. Prove da **fermo**. Mai usare la modalità guida mentre guidi.
5. Annota PASS/FAIL solo dopo averlo visto. Niente «dovrebbe andare». Per ogni FAIL scatta una schermata o un'annotazione con ora.
6. Gravità se FAIL: **P0** = perdita/corruzione di dati o crash all'avvio · **P1** = una funzione principale non funziona · **P2** = difetto parziale o estetico.
7. **Chiusura normale ≠ arresto forzato.** Scorri via l'app dai recenti = chiusura normale (allarmi e lavori devono continuare). «Termina»/«Arresta forzatamente» dalle Info app = arresto forzato: Android disattiva allarmi, lavori e broadcast finché non riapri l'app. Dopo un arresto forzato **nessuna** funzione può girare: non è un difetto di JARVIS.

---

# GRUPPO A — Smoke test immediato (APK isolato, circa 1 ora)

Esito per ogni riga: ☐ PASS ☐ FAIL (gravità: ___). Evidenza = cosa raccogliere.

| # | Test | Preparazione | Azioni | Risultato atteso | Evidenza | Gravità se FAIL |
|---|---|---|---|---|---|---|
| A1 | Installazione | Scarica l'artifact, estrai lo ZIP, `sha256sum` dell'APK | Installa (sopra l'eventuale isolata) | L'hash coincide; installa senza errori; l'app principale è ancora lì | Screenshot icone + output hash | P0 se tocca l'app principale; P1 altrimenti |
| A2 | Avvio a freddo | App chiusa dai recenti | Apri JARVIS | Si apre senza crash; ID build `900c0e3` | Screenshot | P0 (crash) |
| A3 | Permessi | — | Concedi uno alla volta: microfono, notifiche, sveglie e promemoria, posizione (solo se vuoi provare luoghi) | Ogni richiesta è chiara; l'app funziona anche se ne neghi alcuni (non crasha) | Screenshot delle richieste | P1 |
| A4 | Dashboard | — | Guarda Home, tema, orb, riquadri | Si carica; nessuna schermata vuota; stato modello coerente | Screenshot | P2 |
| A5 | Modello AI reale | File `.litertlm` sul telefono | Impostazioni › Modelli › importa e carica | Stato «Pronto» solo dopo il caricamento reale; nessun crash di memoria | Screenshot stato; ora di caricamento | P1 |
| A6 | Chat offline | **Modalità aereo ON** | Scrivi 3 domande diverse | Risposte generate sul telefono; nessun errore di rete; «Stop» interrompe | Screenshot; tempo di prima risposta (a mano) | P1 |
| A7 | Memoria | — | Di' «ricorda che il mio colore preferito è il verde»; poi «qual è il mio colore preferito?» | La conferma richiede il tuo OK; la risposta è corretta; dopo aver chiuso e riaperto l'app, ancora corretta | Screenshot prima/dopo | P1 |
| A8 | Note | — | Crea nota, checklist, cartella; cerca; modifica; chiudi e riapri | Tutto salvato e ritrovato; nessuna perdita di testo | Screenshot | P0 se perdi testo |
| A9 | Attività | — | Crea attività con scadenza, sottoattività, lista; completa e riapri | Stati corretti; scadenze giuste | Screenshot | P1 |
| A10 | Documenti | Un PDF testuale e un DOCX di prova | Importa; chiedi qualcosa che c'è nel documento; poi qualcosa che **non** c'è | Risposta con riferimento alla fonte quando c'è; **ammissione di non sapere** quando non c'è; nessuna fonte inventata | Screenshot delle due risposte | P1 |
| A11 | Traduzione testuale | Modelli lingua scaricati prima (serve internet una volta) | Aereo ON; traduci it→en, en→ja, ja→it | Traduzioni offline; messaggio chiaro se un modello manca | Screenshot | P1 |
| A12 | Traduzione vocale | Pacchetto vocale offline installato | Push-to-talk «Parla Italiano»; poi tocca l'orb di JARVIS **durante** una traduzione | Traduce e legge; l'orb mette **in pausa** il traduttore (nuova regola BETA 8) e non si pestano il microfono; se manca la voce vedi solo il testo | Screenshot; nota su cosa succede all'orb | P1 |
| A13 | Kiwix | Kiwix installato da te + uno ZIM | Biblioteca Offline: rileva, collega cartella, apri uno ZIM; chiedi a JARVIS un fatto presente solo nello ZIM | Si apre in Kiwix; **JARVIS ammette di non poter leggere lo ZIM** | Screenshot | P1 (P2 se solo l'apertura fallisce) |
| A14 | Organic Maps | OM installato con mappa della tua zona | «Cerca una farmacia su Organic Maps»; «Mostra 41.9028, 12.4964 su Organic Maps»; «Portami a [luogo salvato] con Organic Maps» | Si apre OM con ricerca/punto/anteprima percorso; nessun dato inventato; con OM disinstallato JARVIS lo dice senza installarlo | Screenshot | P1 |
| A15 | Modalità guida da fermo | Permesso overlay se richiesto | Avvia; prova controlli; avvia due volte di seguito (voce + pulsante); ferma | Si avvia; il secondo avvio non rompe nulla; alla chiusura sparisce la notifica e torna la modalità precedente | Screenshot barre + notifica; stato modalità dopo lo stop | P1 |
| A16 | Backup locale | — | Impostazioni › Backup: crea backup (**Aereo ON**), «Verifica» | «Integrità verificata: OK» anche in modalità aereo | Screenshot | P1 |

---

# GRUPPO B — Resistenza e background (1 giorno)

| # | Test | Preparazione | Azioni | Risultato atteso | Evidenza | Gravità |
|---|---|---|---|---|---|---|
| B1 | Chiusura interfaccia | Una risposta AI lunga in corso | Scorri via l'app dai recenti | La risposta si completa e arriva la notifica «risposta pronta» | Screenshot notifica | P2 |
| B2 | Riapertura | — | Riapri dopo B1 | La chat mostra la risposta; nessun duplicato | Screenshot | P2 |
| B3 | Schermo spento | Promemoria fra 3 min | Spegni schermo, attendi | Notifica all'ora (±1 min) | Ora attesa vs. ora reale | **P1** |
| B4 | Promemoria in background | Altra app aperta | Come B3 con un'altra app davanti | Stessa notifica; tocco apre l'Attività giusta | Screenshot | P1 |
| B5 | Due notifiche insieme | Due promemoria allo stesso minuto + un backup/automazione | Aspetta | **Entrambi** compaiono, nessuno sostituisce l'altro; aprire la chat non ne cancella altri | Screenshot tendina | P1 |
| B6 | Automazione | Regola oraria fra 3 min | Aspetta; poi cambia ora manualmente di +1 minuto (se vuoi) | Esegue **una sola volta** | Log notifica / ora | P1 |
| B7 | Riavvio del telefono | Promemoria per fra 15 min + regola oraria + briefing di prova | Riavvia, **non aprire JARVIS** | Promemoria e regola arrivano comunque; (se il telefono era spento all'ora: promemoria «mancato» una sola volta) | Ora di arrivo; Diagnostica (stadi `REARM_*`) | **P1** |
| B8 | Riarmo briefing | Orario di briefing fra ~10 min dal riavvio | Dopo B7 aspetta l'orario | Il briefing arriva **senza aver aperto l'app** dopo il riavvio | Screenshot + Diagnostica | **P1** |
| B9 | Cambio fuso/ora | Promemoria domani 08:00 | Cambia fuso orario di sistema | Il promemoria segue l'ora locale nuova; nessun duplicato | Ora di arrivo | P2 |
| B10 | RAM | Impostazioni sviluppatore › Servizi in esecuzione (o `adb shell dumpsys meminfo com.simone.jarvismobile.debug.beta`) | Misura a riposo, durante una risposta AI, durante l'indicizzazione di un PDF, dopo 10 conversazioni | Numeri annotati; nessun crash; la memoria non cresce senza fine | Tabella dei valori **con modello e metodo** | P1 (P0 se crash ripetuti) |
| B11 | Batteria | Batteria 100%, uso normale | Annota % dopo 8 ore con JARVIS inattivo e il servizio automazioni (se attivo) | Consumo non anomalo | Schermata batteria dell'app | P2 |
| B12 | Audio focus / Bluetooth | AirPods collegati | Musica → orb → risposta → traduttore → modalità guida | La musica si abbassa/riprende; la voce esce dove deve; nessun blocco del microfono | Nota per scenario | P1 |
| B13 | Rete assente | Aereo ON per tutto B | Prova A6–A12, backup, briefing | Tutto locale; meteo assente → briefing senza meteo, nessun errore | Screenshot | P1 |
| B14 | Ripristino backup (solo isolata) | Dati fittizi + backup + chiave di recupero salvata | Modifica/cancella i dati fittizi → «Ripristina» → confronta | I dati tornano com'erano; **backup danneggiato e chiave errata sono rifiutati senza perdita** | Prima/dopo, esito dei rifiuti | **P0 se perdi dati** |
| B15 | Chiusura normale vs arresto forzato | Promemoria fra 5 min | 1) chiudi dai recenti → deve arrivare. 2) riprova con «Arresta forzatamente» → **non** arriva finché non riapri l'app (normale) | Comportamento descritto | Annotazione | informativo |
| B16 | Notifiche negate | — | Revoca il permesso notifiche; aspetta un promemoria; riconcedilo; apri Diagnostica | Nessun crash; l'esito «bloccato» è registrato; dopo la riconcessione i promemoria tornano | Screenshot | P2 |

---

# GRUPPO C — Affidabilità prolungata (da non dichiarare superato prima del tempo)

| # | Test | Come | Criterio | Esito |
|---|---|---|---|---|
| C1 | **Tre briefing mattutini consecutivi** | Orario reale, telefono usato normalmente, nessuna app aperta prima | Arriva **3 mattine di fila** all'orario scelto (±5 min), una sola volta ciascuna | ☐ PENDING (finché non sono passate 3 mattine) |
| C2 | Nessun duplicato | Stesse 3 mattine + serale se attivo | Mai due notifiche dello stesso briefing | ☐ PENDING |
| C3 | Persistenza automazioni | Regole attive per ≥3 giorni, con un riavvio in mezzo | Continuano a scattare una volta; nessuna sparita | ☐ PENDING |
| C4 | Errori ricorrenti | Controlla Diagnostica ogni sera | Annota ogni `REARM_STEP_FAILED`, `PROACTIVE_CALL_TIMED_OUT`, `UNKNOWN_EFFECT` | ☐ PENDING |
| C5 | Stabilità nell'uso normale | Uso quotidiano per ≥3 giorni | Nessun crash, nessun blocco, nessuna perdita dati | ☐ PENDING |

**Annotazioni (compila):**
Giorno 1 ___ · Giorno 2 ___ · Giorno 3 ___ · Problemi ricorrenti ___

---

## Quando puoi dire «DEVICE GO — Beta isolata»
Tutte le righe A1–A16 PASS (o i FAIL sono P2 accettati da te) **e** B3, B5, B7, B8, B13 PASS. Il Gruppo C può restare PENDING ma la Beta non è «affidabile nell'uso quotidiano» finché non è passato.
**MAIN APP GO** richiede inoltre i requisiti di BETA 1: copia grezza recuperabile verificata (`verify_raw_backup.py`), `.tar` su PC e su un secondo supporto, backup in-app «Verifica» OK + chiave salvata, procedura di recupero **provata** sull'isolata, B14 PASS, compatibilità firma/versione confermata. **Finché non sono tutti veri, non aggiornare l'app principale.**

## Cosa mi serve da te per chiudere il cerchio
Le righe A/B con PASS/FAIL, gli screenshot dei FAIL, la tabella RAM (B10) e le annotazioni del Gruppo C. Non ho nessun risultato fisico: tutti gli esiti qui sopra sono vuoti per scelta.
