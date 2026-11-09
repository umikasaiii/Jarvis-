# BETA 7/8 — Affidabilità quotidiana: briefing, promemoria, automazioni, modalità guida, backup

Stato: **CI VERIFIED** (codice `c91d4c3`). **NON DEVICE VERIFIED**: nessuna di queste funzioni è stata provata su Honor 200 / MagicOS.
Legenda: *Implementato* = nel codice · *CI* = test automatici passati (JVM o emulatore AOSP) · *Device* = confermato su Honor 200 (nessuno).

## 1. Baseline
- Partenza: `claude/beta06-kiwix-organicmaps` @ `f16e9ce` (verificato). Branch: `claude/beta07-daily-reliability`.
- Nessun merge, `latest-debug` intatto (`550ca02`, job publish saltato), nessuna migrazione Room (versione 15, pinnata da test).
- Nessuna nuova autorizzazione Android, nessun cloud, nessun Core PC, nessun nuovo modello AI.

## 2. Problemi trovati e correzioni
| # | Gravità | Problema (verificato leggendo il codice) | Correzione |
|---|---|---|---|
| 1 | P0 | **Dopo un riavvio (o un aggiornamento, o un force-stop) l'allarme del briefing a orario configurato non veniva ri-prenotato.** `ProactiveScheduler.reconcileConfiguredTime()` usciva subito se il piano salvato era invariato; Android però perde gli allarmi esatti. Stessa cosa per NEXT_ALARM (`KeepExisting`). Il commento in `JarvisApplication` prometteva il contrario. | `scheduleAll(rebook = true)` dal riarmo e dall'avvio app: un piano invariato e futuro viene ri-prenotato con **la stessa revisione** (gli intent in volo restano validi). `ProactiveScheduler` resta l'unico proprietario temporale. |
| 2 | P0 | `BootReceiver`: cinque ricaricamenti in un solo `try`, in una coroutine non gestita. Un errore in uno (es. agenda) saltava gli altri, **briefing compreso**, senza traccia. | `AlarmRearmer` unico punto di riarmo: ogni passo isolato e con timeout (`RearmRunner`), report persistito (solo nomi/stati). Un `RearmWorker` persistente viene accodato **prima** del `goAsync()`: se il processo muore, il passaggio si completa. |
| 3 | P1 | Nessuna reazione a cambio di fuso, ora di sistema, permesso allarmi esatti. | `TimeChangeReceiver` (non esportato): TIMEZONE_CHANGED, TIME_SET, SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED → riarmo (senza geofence, che non dipendono dall'ora). |
| 4 | P1 | Gestore dell'allarme del briefing senza limite di tempo: se `evaluateOnUnlock` si bloccava, il successore non veniva prenotato e `goAsync()` restava aperto. | `BoundedWork.runThenAlways`: lavoro con timeout, successore **sempre** ri-prenotato; nuovo stato diagnostico `PROACTIVE_CALL_TIMED_OUT`. Stesso schema per le regole del motore generico; meteo limitato a 4 s. |
| 5 | P1 | Promemoria con permesso notifiche negato: `return` silenzioso, nessuna traccia. Non si controllavano notifiche disattivate né canale silenziato. | `ReminderNotifier` + `NotificationGate`: esiti POSTED / BLOCKED_PERMISSION / BLOCKED_APP_DISABLED / BLOCKED_CHANNEL / API_THREW registrati in `ReminderDeliveryLedger`. Un post bloccato **non** è segnato consegnato. |
| 6 | P1 | ID notifica promemoria = `6000 + hash & 0xfff` (4096 valori): due promemoria potevano sovrascriversi. | Tag per promemoria + id fisso; il pulsante «Segna come completato» cancella per tag. Test su emulatore con due ID che collidevano nello schema vecchio. |
| 7 | P1 | Promemoria scaduti mentre il telefono era spento: scartati in silenzio al riarmo (solo trigger futuri). | `MissedReminderPolicy`: consegna una sola volta come «Promemoria mancato», entro 12 h, max 5, solo da riavvio/aggiornamento/rete di sicurezza — **mai** da una modifica normale. |
| 8 | P2 | `ReminderScheduler` ignorava l'esito della prenotazione: un allarme rifiutato risultava «schedulato». | Si memorizzano solo le prenotazioni accettate; le altre sono ritentate al sync successivo. |
| 9 | P1 | Automazioni (motore legacy): un allarme consegnato due volte eseguiva la regola due volte. | `AutomationFireGuard` (55 s, tollera orologio spostato indietro); azione con timeout; il successore viene ri-prenotato anche se l'azione fallisce. |
| 10 | P1 | Modalità guida: un secondo avvio prima che il servizio fosse attivo salvava «DRIVING» come modalità precedente (chiudendo si «tornava» a DRIVING). Un servizio morto/avvio fallito lasciava la modalità bloccata. Uno stop a servizio spento lo avviava solo per fermarlo. | `DrivingLifecycle` (puro, testato): start/stop idempotenti, rollback su avvio fallito, ripristino della modalità **una sola volta** in ogni uscita (`onServiceStopped`). |
| 11 | P1 | Servizio guida: `startForeground` con tipo microfono senza `RECORD_AUDIO` → eccezione (Android 14+); chiusura potenzialmente appesa su `saveIfAway()`. | Tipo microfono solo con permesso; chiusura con timeout (6 s) e `stopSelf()` sempre raggiunto; rilascio risorse indipendente. |
| 12 | P1 | Backup: «solo Wi-Fi» era tradotto in un vincolo WorkManager UNMETERED sull'**intero** lavoro: in modalità aereo il backup **locale** non partiva mai. | Nessun vincolo di rete sul lavoro; `CloudUploadGate` decide solo il caricamento cloud (rimandato offline / su rete a consumo con «solo Wi-Fi»). Il cloud resta opt-in. |
| 13 | P2 | Dopo cambio di fuso/ora la scadenza periodica del backup restava calcolata sul vecchio orario. | `BackupScheduler.sync()` incluso in ogni riarmo. |

## 3. Briefing mattutino e serale — stato
- Proprietà temporale invariata: `ProactiveScheduler` (NEXT_ALARM + CONFIGURED_TIME + fallback periodico). `MorningTriggerScheduler` resta solo refresh dati (guardia: nessun `setExact` in quel file; né `AlarmRearmer`/`RearmWorker`/`TimeChangeReceiver` prenotano allarmi da soli).
- Anti-duplicato: invariato (occorrenza durevole `MORNING_DIGEST:<data>`, dispatcher unico, intent obsoleti respinti per revisione). Il riarmo **non** cambia revisione se il piano è invariato, quindi non invalida né duplica.
- Diagnostica già presente e usata: prova di trigger (`TriggerEvidenceStore`: previsto/sparato/valutazione/pianificato), stati dell'occorrenza (`DELIVERED`, `BLOCKED_PERMISSION`, `UNKNOWN_EFFECT`…). Aggiunti `PROACTIVE_CALL_TIMED_OUT`, `REARM_COMPLETED`, `REARM_STEP_FAILED`. `POSTED` significa solo che `notify()` è tornata: **non** prova che l'utente abbia visto la notifica. Nessun contenuto personale nei log.
- **Non verificato**: consegna reale all'orario su MagicOS; il test di **tre mattine consecutive resta PENDING**.

## 4. Comportamento offline
Allarmi, promemoria, regole orarie, backup locale cifrato e modalità guida non richiedono rete. Meteo: se assente, il briefing degrada (nessun dato inventato, nessun servizio nascosto). Cloud backup: facoltativo, spento di default, mai prerequisito del backup locale.

## 5. Backup e ripristino
Il ripristino era già ben protetto (BETA 1): manifest autenticato, compatibilità DB, percorsi sicuri, verifica di tutti gli archivi **prima** di scrivere, backup di sicurezza prima del cutover, staging con verifica per voce. Questo passo aggiunge prove: round-trip, **chiave sbagliata rifiutata**, byte alterato (corpo e tag) rifiutato, archivio troncato/vuoto/spazzatura rifiutato, IV sempre nuovo (`BackupCryptoIntegrityTest`, chiavi software su JVM) e una guardia che fissa l'ordine «verifica → backup di sicurezza → staging».
**Il backup non è completo**: modelli AI, mappe locali e archivi ZIM non sono inclusi (ricorrenti da reimportare); i file ricostruibili sono registrati solo nel manifest. Non è stato eseguito alcun ripristino su installazioni reali; il Keystore su Honor 200 non è provato.

## 6. Restrizioni Android (cosa è dimostrabile e cosa no)
- Dimostrato in CI/emulatore AOSP API 34: allarme prenotato/sostituito/annullato e indipendenza di due chiavi; due notifiche promemoria distinte e cancellazione selettiva; ledger.
- **Solo su dispositivo**: consegna a orario con Doze/standby MagicOS, ripristino reale dopo riavvio, consegna di TIME_SET/TIMEZONE_CHANGED a un'app in background, geofence senza posizione in background (`PlaceGeofenceSource` richiede `ACCESS_BACKGROUND_LOCATION`; non rivisto a fondo in questo passo), visibilità reale delle notifiche, comportamento dei servizi in foreground.
- Batteria MagicOS (suggerimento, non garanzia): Impostazioni › Batteria › JARVIS (build isolata) → consenti «avvio/attività in background» **solo per JARVIS**, evita il risparmio energetico aggressivo per questa app, concedi «Sveglie e promemoria» e notifiche. Non disattivare le protezioni dell'intero telefono. Nessuna di queste impostazioni garantisce l'esecuzione di ogni evento.

## 7. Test realmente eseguiti
- **Core JVM: 1950 test, 0 fallimenti** (+45 in `core/reliability`: `AlarmTime` con DST primaverile/autunnale e cambio fuso, `RearmRunner` isolamento/timeout/annullamento, `BoundedWork`, `MissedReminderPolicy`, `AutomationFireGuard`, `NotificationGate`, `CloudUploadGate`, `DrivingLifecycle`).
- **Android unit JVM (CI): 467 test, 0 fallimenti** — incl. `DailyReliabilityGuardTest` (12 scansioni strutturali) e `BackupCryptoIntegrityTest` (8).
- CI #525: build debug, APK isolato, lint, test strumentati compilati, schema Room, PS 5.1, report APK: verdi; publish saltato.
- **Emulatore** (workflow lanciato a mano sullo stesso commit, run 42611213672: success): `DailyReliabilityInstrumentedTest` (7) + classi precedenti (migrazioni, note, documenti, hub BETA 6). Emulatore AOSP, **non MagicOS**.
- Mappa dei 25 temi richiesti: coperti da core/guard/emulatore tranne — **solo manuali**: consegna reale di notifiche e allarmi, riavvio reale, fuso/ora reali, geofence reale, backup su Keystore reale, ripristino interrotto su dati reali. «Ripristino interrotto / dati invariati» è coperto solo come ordine delle operazioni e dai test BETA 1 di staging (`RestoreStagingTest`), non da un'interruzione reale.

## 8. Test pendenti / limiti
- Tre mattine consecutive del briefing: **PENDING**.
- Nessuna prova di ripristino end-to-end con interruzione né di spazio insufficiente su dispositivo.
- La finestra di recupero (12 h, max 5) e i timeout (8–9 s) sono valori scelti a tavolino.
- Se `TimeChangeReceiver` non riceve gli eventi su MagicOS, i cambi di fuso saranno corretti solo all'avvio successivo.
- `AlarmReceiver` per le regole del motore generico: il timeout interrompe l'azione ma un'azione già parzialmente eseguita non è annullata.

## 9. Commit e CI
- Core `…` (logica pura), `…` (riarmo/briefing/promemoria/guida/backup), `…` (test strumentati), correzioni `ee758eb` (import/`Set.takeLast`), `c91d4c3` (due asserzioni dei test). Questo report: commit successivo, solo docs.
- CI #525: https://github.com/umikasaiii/Jarvis-/actions/runs/42609625398
- Emulatore: https://github.com/umikasaiii/Jarvis-/actions/runs/42611213672
- Run falliti lungo il percorso (errori miei, corretti): #523 (compilazione), #524 (due asserzioni).

## 10. APK isolato
- `com.simone.jarvismobile.debug.beta`, artifact `app-debug-isolated` del run #525.
- Digest dello **ZIP** dell'artifact: `sha256:638efb2ae673fb7a838b0f501aa1b479e70f2497e87071dd1d48782fde39a258`.
- **SHA-256 dell'APK: non ricavabile da questo ambiente** (l'artifact non è scaricabile qui). Calcolarlo con `sha256sum` dopo il download. Condivide i dati con le Beta isolate precedenti (stesso applicationId): nulla è cancellato automaticamente.

## 11. Checklist manuale Honor 200 (APK isolato, telefono fermo)
Promemoria/automazioni: (1) promemoria fra 3 min → notifica arriva; (2) modificane l'orario → il vecchio non arriva; (3) completa un'attività → nessuna notifica; (4) due promemoria nello stesso minuto → due notifiche; (5) automazione oraria → una sola esecuzione; (6) con app chiusa e (7) in modalità aereo; (8) **riavvia il telefono** → promemoria e automazioni successivi arrivano (e un promemoria scaduto durante lo spegnimento compare come «mancato»); (9) cambia fuso orario/ora manuale e ripeti un promemoria.
Briefing: orario di prova vicino (dati non reali); verifica pianificazione, consegna, assenza di duplicati, Diagnostica (stadi, `REARM_*`); **dopo un riavvio il briefing del giorno dopo deve comunque arrivare**; tre mattine consecutive → PENDING finché non fatte.
Modalità guida: avvio (permesso overlay solo se richiesto), doppio avvio rapido (voce + pulsante) → chiudendo torna la modalità originale; Organic Maps/Google secondo la scelta, ritorno a JARVIS; Stop → servizio, overlay, notifica e audio rilasciati; senza permesso microfono non deve andare in errore.
Backup: dati fittizi; backup cifrato; verifica; **in modalità aereo il backup locale deve riuscire**; ripristino solo sull'installazione di prova; confronta i dati; archivio danneggiato e chiave errata → rifiuto senza perdita. Non usare il backup reale dell'app principale.

## 12. Problemi aperti
- **P0**: nessuno noto in CI. Da confermare su dispositivo: ripristino del briefing dopo riavvio (#1/#2).
- **P1**: consegna reale su MagicOS; eventi di fuso/ora; ripristino interrotto non provato su dispositivo; geofence con permesso posizione in background (non rivisto a fondo).
- **P2**: valori di timeout/finestra non tarati; azione di regola interrotta non annullata; ID notifica del motore automazioni e di altri moduli non rivisti.

## 13. Rischi per BETA 8
Passaggio finale/hardening: valutare firma release e rimozione degli accessi di debug; una prova fisica complessiva (riavvio, aereo, tre mattine); revisione di `PlaceGeofenceSource` e dei servizi in foreground con i limiti Android 16; unificare la diagnostica di consegna dei promemoria in una schermata visibile; decidere se i test strumentati vadano eseguiti su ogni push.
