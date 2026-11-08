# BETA 5/8 — Traduttore offline vocale e testuale

Stato: **CI VERIFIED**. **NON DEVICE VERIFIED**: nessuna prova su Honor 200; riconoscimento vocale, ML Kit, voci TTS e modelli reali non sono stati provati.
Legenda: *Implementato* = nel codice · *CI* = coperto da test automatici passati · *Device* = confermato su Honor 200 (nessuna funzione lo è ancora).

## 1. Baseline e commit
- Partenza: branch `claude/beta04-offline-document-rag`, commit `2e7b687` (verificato). Branch: `claude/beta05-offline-translator`.
- Codice: `b830d57`. Questo report: commit successivo, solo docs.
- Nessuna modifica a Room (versione 15), nessun merge, `latest-debug` non toccato (job publish saltato).

## 2. Cosa è stato riusato e cosa è cambiato
Riusati senza riscrittura: `TranslationModelManager`, `MlKitTranslationEngine`, `MlKitLanguageDetector`, `TranslationAudioController`, `LiveTranslatorRepository`, `LiveTranslatorScreen/ViewModel`, e in `:core` `LanguageStabilizer`, `LivePairRouter`, `TranslationMode`, `TranslationSession`, gli stati.
**Cambiamento architetturale mirato:** la logica di controllo che stava dentro `LiveTranslatorManager` (ciclo, push-to-talk, stop, stato) è stata estratta in `:core` come `TranslatorController`, senza tipi Android, collegata alle parti reali tramite porte (`Recognizer`, `Speaker`, `LanguageDetector`, `ModelGate`, `TranslatorPrefs`). Motivo: era impossibile provare con test automatici concorrenza e stop. `LiveTranslatorManager` è ora solo un adattatore (stessa API pubblica: `SessionCoordinator`, `DuplexMonitorController` e `SileroVad…` non sono stati toccati). Nessun cloud, nessun LiteRT-LM, nessun Core PC (verificato da test di scansione sorgenti).

## 3. Problemi trovati nel codice precedente e correzioni
| # | Problema | Correzione |
|---|---|---|
| 1 | Push-to-talk: ogni pressione lanciava una coroutine non tracciata; due pulsanti insieme aprivano due riconoscimenti | un solo turno alla volta; un secondo pulsante durante l'ascolto/elaborazione è ignorato con avviso «Sto ancora elaborando»; lo stesso pulsante di nuovo annulla l'ascolto |
| 2 | Dopo Stop un turno già avviato poteva ancora tradurre, aggiungere un segmento, parlare e rimettere lo stato a «Ready» | ogni start/stop/pausa/cambio modalità incrementa un'epoca; un turno di epoca vecchia non può più pubblicare stato, segmenti, avvisi né parlare (anche in un nuovo avvio rapido) |
| 3 | `start` e `stop` non erano coordinati: uno Stop durante il controllo/scaricamento modelli veniva sovrascritto | il job di avvio è cancellabile e protetto dall'epoca |
| 4 | Errori transitori del riconoscimento: ripartenza immediata all'infinito | attesa crescente e, dopo 5 errori, stato di errore con messaggio |
| 5 | Modello inglese: ML Kit traduce fra due lingue non inglesi passando dall'inglese, ma si controllavano solo i due modelli scelti (es. it↔ja falliva offline) | modelli richiesti = coppia + inglese se serve (`TranslationModels.required`), mostrato nell'interfaccia. **Derivato dalla documentazione ML Kit, da confermare sul dispositivo** |
| 6 | Tentativo di scaricare i modelli anche in modalità aereo / senza Wi-Fi (se «solo Wi-Fi») | il download parte solo se c'è rete e la politica lo consente; altrimenti errore chiaro «modello mancante» |
| 7 | Voce TTS mancante: la frase veniva comunque inviata al motore (voce sbagliata o silenzio) | `speak` ora restituisce SPOKEN / NO_VOICE / FAILED; con NO_VOICE nulla viene letto, il testo resta a schermo con avviso |
| 8 | Traduzione fallita: scartata in silenzio | avviso visibile; il ciclo prosegue |
| 9 | Lingua non installata nel riconoscitore: errore tecnico `stt_unavailable:language:ja-JP` | messaggio in italiano con cosa fare; la traduzione di testo continua a funzionare |
| 10 | Eco/duplicati: solo la sequenzialità ascolto/voce | in più: pausa di assestamento (350 ms) dopo la lettura, scarto di un risultato simile alla traduzione appena letta (8 s) o identico al precedente (3 s) |
| 11 | Pulsante «scambia» inattivo se non c'è sessione; Pausa/Riprendi non presenti nella schermata; modalità non cambiabile durante la sessione | scambio della coppia salvata a riposo; pulsanti Pausa/Riprendi; cambio modalità dal vivo |
| 12 | Traduzione di testo inesistente | scheda «Traduci un testo» |

## 4. Funzioni disponibili (tutte: Implementato; logica: CI; comportamento reale: Device PENDING)
- **Testo offline senza microfono**: scrivi/incolla (max 4000 caratteri), direzione A→B o inversa, Traduci, Leggi (se esiste la voce), Copia. Non avvia sessioni né microfono; non scarica nulla: se manca un modello lo dice.
- **Lingue**: italiano, inglese, giapponese, francese, spagnolo.
- **Push-to-talk**: due pulsanti («Parla Italiano» / «Parla English»), un turno alla volta, annulla con un secondo tocco, disabilitati mentre l'altro lato è in elaborazione. Legge la traduzione nella lingua di destinazione.
- **Automatico / Conversazione / Solo ascolto / Solo testo / Voce+testo**: ciclo sequenziale ascolto → traduzione → lettura.
- **Limite dichiarato e mostrato nell'interfaccia**: il riconoscimento vocale offline ascolta UNA lingua alla volta. In Automatico ascolta nella lingua attiva e passa all'altra dopo due rilevamenti concordi: **la prima frase dell'altro interlocutore viene tradotta come se fosse nella lingua attiva** (un test lo dimostra) e può essere persa. Per risultati affidabili usare Push-to-talk. Non è riconoscimento simultaneo di due lingue.
- **Modelli ML Kit**: stato per lingua, scarica/riprova, indicazione del modello inglese «necessario per tradurre»; download solo con rete e secondo la regola «solo Wi-Fi».
- **Riconoscimento solo sul dispositivo** (`createOnDeviceSpeechRecognizer` + `PREFER_OFFLINE`, verificato da test di scansione); lingua non disponibile gestita con messaggio.
- **Stop/Pausa/Riprendi/Scambio/Cambio modalità**: semantica e test al §6.

## 5. Limiti reali
- Dipende dal pacchetto vocale offline del dispositivo per ciascuna lingua (soprattutto giapponese): se manca, il riconoscimento di quella lingua non funziona (messaggio chiaro; resta il testo).
- La prima installazione dei modelli ML Kit richiede Internet (scaricati da Google, una volta); poi tutto è offline.
- Voce TTS: usa Google TTS se presente, altrimenti il motore di sistema; se manca la voce offline si vede solo il testo. All'occorrenza viene aperta la schermata di sistema per installare i dati vocali (una volta per sessione).
- Qualità di traduzione e di riconoscimento: quelle di ML Kit / del riconoscitore di sistema, non valutate.
- Latenza, consumi, comportamento con cuffie/AirPods e con il volume a zero: non misurati.
- Il rilevamento della lingua (ML Kit) su frasi brevi è incerto; il filtro anti-oscillazione può ritardare il cambio di lato.
- L'attesa di assestamento (350 ms) e le finestre eco/duplicato (8 s/3 s) sono valori scelti a tavolino, da tarare sul telefono.
- Nessuna trascrizione viene salvata (solo le preferenze), invariato.

## 6. Test eseguiti
- **Core JVM: 1873 test, 0 fallimenti** (+41). `TranslatorControllerTest` (31, con riconoscitore/motore/voce/modelli simulati): traduzione bidirezionale push-to-talk e automatica (con il limite del primo cambio lingua), rilevamento fuori coppia, modello mancante (offline: nessun download, nessun ascolto), pivot inglese, download online, download fallito, testo senza microfono (ok, vuoto, troppo lungo, stessa lingua, modello mancante, errore del motore), concorrenza push-to-talk (due pulsanti, doppio tocco, durante la lettura, fuori modalità), **stop durante ascolto / traduzione / lettura** (nessun segmento, nessuna voce, nessun nuovo ascolto), risultato tardivo di una sessione vecchia, doppio start, pausa/ripresa, scambio durante un turno, cambio modalità dal vivo (anche in pausa), traduzione fallita, errori ripetuti con attesa e stop, eccezione del riconoscitore, lingua non disponibile, voce mancante, TTS disattivata, modalità solo testo, eco, duplicati, ascolto mai sovrapposto alla lettura. `TranslatorSupportTest` (eco, giapponese, duplicati, messaggi per ogni stato/avviso, limite Automatico).
- **Android unit JVM (CI):** `TranslatorGuardTest` (7 scansioni dei sorgenti: nessuna API cloud e nessun riferimento a LLM/Core PC, manager solo adattatore, riconoscimento solo on-device, nessuna lettura con voce sbagliata, download condizionato a rete e regola Wi-Fi, interfaccia con testo/pausa/modalità live/limite dichiarato, Room invariato).
- CI: APK debug, APK isolato, lint, test strumentati compilati, schema Room, PS 5.1: tutti verdi.
- **Emulatore: non eseguito per questo passo.** Il workflow emulatore parte solo se cambiano file di database/archivio/documenti; non ho aggiunto test strumentati perché ML Kit, il riconoscimento vocale e le voci TTS non sono verificabili in modo deterministico su un emulatore di CI (servono modelli scaricati e pacchetti vocali). Quindi: **nessuna parte del traduttore è stata provata con componenti Android reali.**
- Mappa dei test richiesti: traduzione bidirezionale ✔, modello mancante ✔, modalità offline ✔, concorrenza push-to-talk ✔, stop durante STT/traduzione/TTS ✔, cancellazione ✔, errori ✔, risultati obsoleti ✔ — tutti sul controller con componenti simulati, quindi verificano la logica, non l'inferenza né l'audio.

## 7. CI
- CI #517: https://github.com/umikasaiii/Jarvis-/actions/runs/42535192242 (commit `b830d57`, success)

## 8. APK isolato (`com.simone.jarvismobile.debug.beta`, non sovrascrive l'app principale)
- Artifact `app-debug-isolated`: https://github.com/umikasaiii/Jarvis-/actions/runs/42535192242/artifacts/13638963981
- SHA-256 dello **zip**: `96acce4d1fc51d4800fdbccd0e77f30819e28b28f9743a2ba6d8f215283b9c0c`
- SHA-256 dell'**APK** all'interno: non estratto da me; calcolalo dopo il download con `sha256sum app-debug.apk` (identità APK nell'artifact `apk-report` 13639078986).
- Testa questo APK: il push del report avvia un'altra CI con un APK diverso.

## 9. Checklist Honor 200
**Preparazione (con Internet, una volta):** Traduttore → scarica italiano, English e giapponese (e verifica che compaia «English (necessario per tradurre)» per it↔ja); in Impostazioni di sistema controlla che esista il riconoscimento vocale offline per italiano e inglese (giapponese facoltativo). Poi attiva la **modalità aereo**.
**Testo (nessun microfono):**
1. Italiano→English: «Dove si trova la stazione?» → traduzione sensata, tempo percepito.
2. «Inverti» e traduci la risposta in italiano. Prova «Leggi» in inglese e in giapponese: se manca la voce deve comparire l'avviso, senza voce sbagliata.
3. Testo vuoto, testo molto lungo, coppia con un modello cancellato (cancella English e riprova it↔ja): messaggio «modello mancante», nessun blocco.
**Push-to-talk:**
4. Modalità Push-to-talk, Avvia. Tocca «Parla Italiano», di' una frase → trascrizione, traduzione, lettura in inglese. Ripeti con «Parla English».
5. Tocca i due pulsanti quasi insieme: deve esserci un solo ascolto e l'avviso «Sto ancora elaborando».
6. Tocca lo stesso pulsante due volte durante l'ascolto: l'ascolto si annulla, nessun testo.
7. Premi «Ferma» durante ascolto, durante «Traduco…» e mentre legge: nessun testo o voce comparsi dopo; stato «Pronto ad avviare». Riavvia: nessun residuo della sessione precedente.
8. «Pausa» durante l'ascolto → nessuna trascrizione tardiva; «Riprendi» riparte. «Scambia» durante la sessione; cambia modalità a sessione attiva.
**Automatico/Conversazione:**
9. Parla italiano e, con un'altra persona (o un secondo telefono), inglese: verifica che la **prima frase** in inglese venga tradotta male/persa e che dalla seconda il lato cambi. Annota quante frasi servono.
10. Verifica l'eco: con il volume alto l'app non deve ritradurre la propria voce; annota ogni caso in cui succede (dato utile per tarare 350 ms/8 s).
11. Prova con cuffie/AirPods e con altoparlante: da dove esce la traduzione, dove è il microfono. Prova con la lingua giapponese senza pacchetto vocale: messaggio chiaro.
12. Annota: tempo di avvio sessione, latenza per frase, consumo batteria/RAM, crash. Non inventare i valori.
Stato dopo l'esito: DEVICE VERIFIED solo per i punti che confermi.
