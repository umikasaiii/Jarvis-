# BETA 8/8 — Integrazione finale, stabilizzazione e release candidate isolata

**Stato: CI VERIFIED. NON DEVICE VERIFIED.** Nessuna delle otto Beta è stata provata sull'Honor 200 / MagicOS. Questo documento non dichiara la Beta «funzionante sul telefono».
Legenda: *CI* = test automatici passati (JVM o emulatore AOSP API 34) · *Device* = confermato su Honor 200 (nessuno).

## 1. Stato iniziale
- Partenza: `claude/beta07-daily-reliability` @ `b0a06d4` (verificato). Branch: `claude/beta08-final-integration`.
- Il branch contiene cumulativamente le modifiche di BETA 1–7 (verificato con `git log f16e9ce..HEAD`: BETA 7 + BETA 8 sopra BETA 6, che sta sopra le precedenti).
- Nessun merge, nessuna PR, `latest-debug` intatto (`550ca02`), nessuna migrazione Room (versione 15), nessun dato toccato.

## 2. Audit cumulativo (cosa ho realmente guardato)
Revisione mirata del codice, non una ri-verifica di ogni schermata. Aree: chat/AI locale, note/attività, documenti/RAG, traduttore, Kiwix/Organic Maps, proattività/promemoria/automazioni/modalità guida/backup (già a fondo in BETA 7), componenti esportati, PendingIntent, permessi, geofence, notifiche, log. Le funzioni storiche (dashboard/tema, bolla, Modalità Pro, archivio, navigatore interno, salute) **non sono state modificate** e restano coperte dai loro test preesistenti; «compila» non è stato trattato come «verificato».

## 3. Problemi trovati
| # | Gravità | Problema (riprodotto leggendo il codice) |
|---|---|---|
| 1 | **P1** | **Assistente e traduttore condividono UN solo riconoscitore vocale** (`SpeechToTextEngine` singleton). Una nuova `transcribe()` sostituisce la precedente: toccando l'orb durante una traduzione, o avviando il traduttore mentre l'assistente ascolta, i due si annullano a vicenda e il ciclo del traduttore ricomincia in continuazione. |
| 2 | **P1** | **Collisioni fra notifiche di moduli diversi** (ID senza tag): l'avviso di backup fallito usava 7100, lo stesso ID della notifica del servizio di navigazione, e la sostituiva; le automazioni usavano 5000–9095 (comprende 7100). |
| 3 | **P1** | `clearResponseNotifications()` cancellava **tutte** le notifiche con ID 3000–7095 quando si apriva la chat: oltre alle «risposta pronta», anche promemoria e risultati di automazioni nello stesso intervallo. |
| 4 | P2 | `BootReceiver` esportato senza necessità (riceve solo broadcast di sistema; `QUICKBOOT_POWERON` non è protetto, quindi un'altra app poteva innescare un riarmo, innocuo ma superfluo). |
| 5 | info | `PlaceGeofenceSource` (non rivisto in BETA 7): nessun difetto trovato — vedi §8. |

Conflitti della lista del §3 della richiesta: esito per ciascuno nel §6 (riprodotto/corretto, già coperto, o non riproducibile qui).

## 4. Correzioni
- **#1** `MicrophonePolicy` (`:core`, 3 test): un avvio esplicito dell'assistente mette **in pausa** il traduttore (riprendibile dalla sua schermata); l'avvio del traduttore mentre l'assistente cattura voce **annulla** quel turno. Un traduttore avviato dal comando vocale dell'assistente non taglia la risposta di conferma (a quel punto il turno non sta più ascoltando). Nessun secondo orchestratore: due righe in `SessionCoordinator`.
- **#2/#3** Namespace di notifica per modulo: tag `jarvis.backup`, `jarvis.automation`, `jarvis.response` (più `jarvis.reminder.<id>` di BETA 7). La pulizia delle «risposte pronte» ora filtra per tag, non per intervallo di ID. Le notifiche già nella tendina di build precedenti (senza tag) non vengono più cancellate dalla pulizia: effetto trascurabile.
- **#4** `BootReceiver` `exported="false"` (BOOT_COMPLETED/MY_PACKAGE_REPLACED sono broadcast di sistema, consegnati anche ai receiver non esportati). **Da confermare con un riavvio reale.**
- Nessuna ottimizzazione speculativa è stata introdotta.

## 5. Test automatici (eseguiti, non solo compilati)
- **Core JVM: 1953 test, 0 fallimenti** (locale; la CI esegue la suite completa nel job «Core unit tests»: verde).
- **Android unit JVM (CI #529): 475 test, 0 fallimenti** — include `FinalIntegrationGuardTest` (7 controlli strutturali: arbitraggio microfono, namespace notifiche, componenti esportati, flag dei PendingIntent, geofence, permessi vietati, log senza testo utente, Room v15) e i guard di BETA 1–7.
- **Lint, schema Room esportato, compilazione dei test strumentati, build APK debug e isolata, PS 5.1: verdi** (CI #529).
- Errori miei lungo il percorso, tutti nei test di controllo e corretti: #528 (un test di guardia leggeva il KDoc invece della chiamata).

## 6. Test su emulatore (eseguiti)
Workflow «DB migration tests (emulator)» lanciato a mano sul commit finale `900c0e3`: **run 42619205325, success** (e già verde su `441ee57`, run 42617238489, con lo stesso codice strumentato). Classi eseguite: migrazioni Room storiche (`JarvisMigrationInstrumentedTest`), `NotesStoreInstrumentedTest`, `DocumentPipelineInstrumentedTest`, `OfflineHubIntentInstrumentedTest`, `DailyReliabilityInstrumentedTest`, **`CrossModuleInstrumentedTest`** (nuovo: passaggio di riarmo reale nel processo con l'intero grafo Hilt, idempotenza, e notifiche di moduli diversi che non si sostituiscono né si cancellano a vicenda). Emulatore AOSP API 34 x86_64: **non è MagicOS** e non dimostra la consegna reale di allarmi o notifiche.
Limite di lettura: ho verificato l'esito del workflow (success), non il conteggio di ogni singolo test strumentato.

### Mappa dei 25 scenari richiesti
| # | Scenario | Copertura reale |
|---|---|---|
| 1 | Avvio con modello mancante | Logica JVM (BETA 2); non end-to-end |
| 2 | Avvio con memoria presente | Test archivio/memoria esistenti (BETA 2–3), emulatore per note |
| 3 | Chat e memoria persistente | JVM (BETA 2); non provato con un modello reale |
| 4–5 | Nota / attività via chat | JVM (`CommandMatcher`/archivio, BETA 3) + emulatore `NotesStore` |
| 6–7 | Documento via AI / nessuna evidenza | JVM + emulatore pipeline documenti (BETA 4); l'AI reale non provata |
| 8 | Errore in indicizzazione | Pipeline documenti (BETA 4) |
| 9 | Arresto AI durante richiesta | Logica di annullamento (BETA 2/6d), non su modello reale |
| 10 | Chat → traduttore | **Nuovo**: `MicrophonePolicy` + guard |
| 11 | Stop traduttore durante TTS | `TranslatorControllerTest` (BETA 5) |
| 12–13 | Modalità guida con Organic Maps / interrotta | `OfflineHub` JVM + emulatore intent (BETA 6); `DrivingLifecycle` (BETA 7) |
| 14–15 | Briefing e promemoria nello stesso intervallo / due promemoria nello stesso minuto | Namespace/tag distinti: emulatore (BETA 7–8) |
| 16 | Riarmo degli allarmi | `RearmRunner` JVM + **riarmo reale su emulatore** |
| 17 | Backup durante modifiche | **Non coperto** (il backup copia i file; nessun test di concorrenza con scritture) |
| 18 | Ripristino simulato e confronto | Test di staging/compatibilità (BETA 1); nessun ripristino end-to-end con confronto |
| 19 | Permessi revocati | `NotificationGate`/geofence inerte (JVM/guard); non provato su dispositivo |
| 20 | Modalità aereo | Logica locale per costruzione (`CloudUploadGate`, nessun cloud nei moduli); non provata in aereo reale |
| 21–22 | Kiwix assente / Organic Maps non disponibile | Emulatore (BETA 6), app esterne reali non provate |
| 23 | Chiusura e ripresa del processo | `RearmWorker`/WorkManager persistente; non provato con kill reale |
| 24 | Aggiornamento Beta isolata mantenendo i dati | CI «APK identity & update-compatibility»: stesso certificato; migrazioni Room testate; **installazione sopra non provata** |
| 25 | Nessun `latest-debug` involontario | Job `publish` **SKIPPED** in CI #529 + step «Verify release-publish guard» verde |

## 7. Prestazioni misurate
**Nessuna misura su Honor 200.** Nessuna latenza, RAM, batteria o consumo è stato misurato in questo passo, né in quelli precedenti; non ne dichiaro. Dalla CI: tempo di build APK ≈ 5 min, lint ≈ 2 min (runner GitHub, non rappresentativo del telefono). Revisione statica di punti critici (servizi, receiver, coroutine, worker): i receiver in `ContextEngine`/`AutomationEventService`/dashboard hanno la registrazione bilanciata; i lavori dei receiver sono ora limitati (BETA 7); non ho trovato nuovi accessi a Room sul thread principale né worker duplicati con chiave unica, ma **non è un profilo di prestazioni**. Da misurare sul telefono (vedi piano di prova, gruppo B).

## 8. Sicurezza
- Esportati nel Manifest: solo `MainActivity`, `ViewPermissionUsageActivity` (requisito Health Connect), `JarvisNotificationListener` e `JarvisTileService` (entrambi protetti da permesso di sistema `BIND_*`). Fissato da test. `BootReceiver` ora non esportato.
- PendingIntent: tutti dichiarano `IMMUTABLE`/`MUTABLE`; l'unico mutabile (proximity alert) ha componente esplicito non esportato. Test di scansione.
- Permessi vietati assenti (nessun `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES`, Accessibility, ottimizzazione batteria, contatti/SMS/chiamate). `allowBackup="false"`.
- Log: nessun testo utente nei moduli toccati (scansione); non è un audit di tutti i log dell'app.
- **`PlaceGeofenceSource`**: inerte senza `ACCESS_BACKGROUND_LOCATION` (Android 11+: concesso a parte dall'utente); rimuove i vecchi allarmi prima di registrarne di nuovi; usa `LocationManager.addProximityAlert` (API deprecata dal livello 29: su MagicOS il comportamento è non provato e potrebbe non essere affidabile); i geofence **non sopravvivono** a riavvio (ri-registrati dal passaggio di riarmo), a «Arresta forzatamente» né a revoca del permesso (rimozione al sync successivo). Nessun tentativo di aggirare l'arresto forzato.
- **Situazioni distinte (non equivalenti):** app in background (allarmi esatti e WorkManager attivi, soggetti a Doze/MagicOS) · processo terminato dal sistema (allarmi e lavori persistenti restano, servizi in foreground no) · **arresto forzato dall'utente** (Android cancella allarmi, lavori, geofence e blocca i broadcast finché l'utente non riapre l'app: nessuna funzione di JARVIS può girare — è il comportamento del sistema, non un difetto) · riavvio (allarmi persi, riarmati dal receiver di avvio) · aggiornamento APK (come il riavvio, via `MY_PACKAGE_REPLACED`).
- Documenti ostili, comandi AI che modificano dati, restrizioni FGS di Android 15/16: coperti dai test e dai limiti dei passi precedenti (BETA 3–6); **non riesaminati qui** e non provati su Android 16 reale.

## 9. Backup
Backup cifrato AES-GCM (chiave contenuto + avvolgimento Keystore), manifest autenticato, verifica di tutti gli archivi **prima** di scrivere, backup di sicurezza prima del cutover, staging verificato per voce (BETA 1/7). Prove automatiche: round-trip, chiave errata rifiutata, archivio alterato/troncato/vuoto rifiutato, copertura dei file privati (`BackupCoversPrivateUserFilesTest`).
**Dati esclusi** (da reimportare): modelli AI, mappe offline, archivi ZIM, altri file voluminosi/ricostruibili (registrati solo nel manifest). Il backup non è «completo» in questo senso.
**Non verificato:** ripristino sull'installazione di prova su telefono, interruzione durante il ripristino, spazio insufficiente reale, backup durante modifiche concorrenti, Keystore su Honor 200. Il sistema **non** è dichiarato completamente verificato.

## 10. Compatibilità Android
`minSdk 31`, `targetSdk 35`, `compileSdk 35`, ABI `arm64-v8a` (nel solo APK; la CI emulatore aggiunge x86_64 solo per i test). Non provato su Android 16 / MagicOS. Il receiver di fuso/ora (BETA 7) e quelli di boot sono dichiarati nel Manifest: la consegna su MagicOS a un'app in background è da verificare.

## 11. Matrice delle funzionalità
Colonne: Impl. · JVM · Android unit/guard · Emulatore · Honor 200 · Offline reale verificato · Problemi aperti · Gravità · **Stato**. Honor 200 = **NO** ovunque; «Offline reale» = **non verificato** ovunque (le funzioni sono offline per costruzione, non provate in modalità aereo).

| Funzione | Impl. | JVM | Android unit | Emulatore | Problemi aperti | Gr. | Stato |
|---|---|---|---|---|---|---|---|
| AI locale (LiteRT-LM, chat, interruzione) | sì | sì | sì | no | modello reale mai provato qui; nessuno streaming a token | P1 | PARTIAL |
| Memoria personale / cronologia | sì | sì | sì | parziale | — | P2 | CI VERIFIED |
| Note, cartelle, checklist, ricerca | sì | sì | sì | sì | — | P2 | CI VERIFIED |
| Attività, sottoattività, liste, scadenze | sì | sì | sì | no | ricorrenze non implementate | P2 | PARTIAL |
| Documenti / RAG | sì | sì | sì | sì | ricerca lessicale; no OCR; fonti non cliccabili; AI reale non provata | P2 | PARTIAL |
| Traduttore (testo, push-to-talk, modalità) | sì | sì | sì | no | niente trascrizione simultanea di 2 lingue; ML Kit/voci reali non provati; arbitraggio microfono nuovo | P1 | PARTIAL |
| Kiwix / biblioteca ZIM | sì | sì | sì | sì (finto) | AI non legge gli ZIM (dichiarato); app reale non provata | P2 | PARTIAL |
| Organic Maps / provider navigazione | sì | sì | sì | sì (finto) | app reale non provata | P2 | PARTIAL |
| Briefing mattutino/serale | sì | sì | sì | no | consegna su MagicOS; 3 mattine PENDING | **P1** | PARTIAL |
| Promemoria e notifiche | sì | sì | sì | sì | consegna reale a orario non provata | P1 | PARTIAL |
| Automazioni (legacy + motore regole) | sì | sì | sì | no | — | P2 | CI VERIFIED |
| Riarmo dopo riavvio/aggiornamento/fuso | sì | sì | sì | sì | solo riavvio reale lo conferma | P1 | PARTIAL |
| Geofence luoghi | sì | no | guard | no | API deprecata; MagicOS non provato | P1 | NOT TESTED |
| Modalità guida (overlay + V2) | sì | sì | guard | no | MagicOS/permessi overlay non provati | P1 | PARTIAL |
| Navigatore interno / mappe | sì (storico) | sì | — | no | non toccato in Beta 1–8 | — | PARTIAL |
| Backup locale cifrato | sì | sì | sì | no | ripristino su telefono non provato | **P1** | PARTIAL |
| Ripristino da backup | sì | sì | sì | no | nessun end-to-end con confronto dati | **P1** | PARTIAL |
| Backup cloud (Drive, opzionale) | sì | sì | sì | no | disattivato di default; non provato | P2 | NOT TESTED |
| Sicurezza / esportazioni / permessi | sì | — | sì | — | audit statico, non penetration test | P2 | CI VERIFIED |
| Dashboard/tema ARES, bolla, Modalità Pro, Archivio, Salute | sì (storico) | sì | sì | no | invariati; non rivalidati sul telefono | P2 | PARTIAL |
| APK isolato firmato debug | sì | — | — | — | firma di debug (§12) | P2 | CI VERIFIED |

Nessuna riga è DEVICE VERIFIED; nessuna è BLOCKED.

## 12. P0 / P1 / P2 ancora aperti
- **P0:** nessuno noto in CI.
- **P1:** (1) nessuna prova su Honor 200 di nessuna funzione; (2) consegna reale di briefing/promemoria/allarmi su MagicOS e ripristino dopo riavvio (la correzione del briefing dopo riavvio di BETA 7 è non provata sul telefono); (3) ripristino da backup su telefono e interruzione; (4) modello AI e traduzione/voce reali; (5) geofence con permesso in background e API deprecata; (6) `BootReceiver` non esportato: da confermare con riavvio.
- **P2:** ricerca documenti lessicale; niente OCR; fonti non cliccabili; niente streaming; niente trascrizione simultanea di due lingue; ricorrenze attività; prestazioni mai misurate; timeout/finestre di BETA 7 non tarati; ID notifica del servizio di navigazione fisso (untagged, deliberato); backup durante modifiche non testato.

## 13. Limitazioni conosciute (invariate, non nascoste)
Nessuna interrogazione AI degli ZIM · ricerca documentale prevalentemente lessicale · nessun OCR dei PDF scansionati · fonti non cliccabili in chat · nessuno streaming a token · nessuna trascrizione simultanea di due lingue · ricorrenze attività non implementate · prestazioni su Honor 200 non misurate · modalità guida e notifiche su MagicOS pendenti.

## 14. Release candidate — identificazione
**APK di riferimento = build del commit di codice `900c0e3de83f8a8ae7e360bde2bcb887f43cd5ba`** (CI #529). Un commit successivo che modifica solo `docs/` può produrre un'altra build: **non** usarla come riferimento.
- Application ID: `com.simone.jarvismobile.debug.beta`
- Version Code / Name: `1` / `0.1.0` (invariati da sempre: **lo stesso versionCode dell'eventuale installazione isolata già presente** — l'aggiornamento sopra va tentato con `adb install -r` o installer, non è stato provato; non scendere mai a un versionCode inferiore)
- BUILD_ID (mostrato in app): `900c0e3` (7 caratteri del commit)
- Firma: certificato **«CN=Android Debug, O=Android, C=US»**, schema APK v2, RSA 2048, SHA-256 `33018d7f10c4a1aa07cc1e609ba541caf1cd318d7e670556f9c826d29b07c8fb` (SHA-1 `b9a7fa012e4a1f04dd97bd0a554d99b259e9dc41`)
- minSdk 31, targetSdk 35, compileSdk 35, ABI `arm64-v8a`, `debuggable=yes`, 29 permessi dichiarati
- Dimensione APK: 217.786.738 byte
- **SHA-256 dell'APK (file effettivo):** `736f53ad90298b8e823452823177282cd10c53f200e5bed1614d7e7e4bb49248` (calcolato in CI dal job «Isolated test APK», `sha256sum`)
- **SHA-256 dello ZIP dell'artifact** (distinto): `bd870e1d112fbb5a67b2ddc7f3fe4b89eca8d6ffbf7ce80563edcfaeae0fb1bc`
- Artifact: `app-debug-isolated` (ID 13675112073, 97.327.060 byte) nel run https://github.com/umikasaiii/Jarvis-/actions/runs/42619188240 — **scade il 2027-02-07T12:42:02Z**
- Non distribuita su `latest-debug` né su alcun tag.
- Verifica dopo il download: `sha256sum app-debug-isolated.apk` deve dare il valore sopra.

### Firma e futuro (non fatto)
La firma è il **certificato di debug committato**: adatto a una beta isolata personale, **non** a una distribuzione pubblica. Per una release firmata servirebbero: un keystore di rilascio generato fuori dal repository e custodito (mai committato), `versionCode` crescente, un piano di compatibilità con i dati dell'app principale (cambiare certificato impedisce l'aggiornamento in-place: i dati andrebbero migrati con backup/ripristino), build `release` con R8 (già configurata a leggere il keystore da variabili d'ambiente) e test completo di quella build. Non modificata la firma dell'app principale.

## 15. Link CI
- CI #529 (codice finale): https://github.com/umikasaiii/Jarvis-/actions/runs/42619188240 — tutti i job verdi, `publish` skipped
- Emulatore sul commit finale: https://github.com/umikasaiii/Jarvis-/actions/runs/42619205325 — success
- Emulatore su `441ee57`: https://github.com/umikasaiii/Jarvis-/actions/runs/42617238489
- Falliti lungo il percorso (errori miei nei test di controllo): CI #528

## 16. Esiti GO / NO-GO
- **CI GO: GO** — build verde, test pertinenti verdi, nessun P0 noto, dati protetti (nessuna migrazione, `latest-debug` intatto), APK identificato e verificabile con SHA-256.
- **DEVICE GO — Beta isolata: NO-GO (non ancora concedibile)** — mancano tutte le prove essenziali sull'Honor 200 (Gruppo A del piano). Si valuta solo dopo di esse; non richiede l'aggiornamento dell'app principale.
- **MAIN APP GO: NO-GO** — restano validi tutti i requisiti GO/NO-GO di BETA 1: copia grezza recuperabile verificata, backup in-app con «Verifica» OK e chiave salvata, procedura di recupero **provata**, test di migrazione sull'installazione reale, compatibilità firma/versione, risultati sul dispositivo. **L'aggiornamento in-place dell'app principale resta vietato.**

## 17. Riepilogo per l'utente (senza tecnicismi)
Ho riunito tutto il lavoro delle otto Beta in un solo APK di prova, separato dalla tua app vera, che non viene toccata. Ho cercato i punti in cui le parti dell'app si danno fastidio a vicenda e ne ho corretti tre: il traduttore e l'assistente non si «rubano» più il microfono, le notifiche di funzioni diverse non si cancellano o sostituiscono più, e un componente interno non è più raggiungibile da altre app. Tutti i controlli automatici passano, anche su un telefono simulato. **Ma nessuna prova è stata fatta sul tuo Honor 200**: non posso dirti che sveglie, notifiche, traduzione vocale o modalità guida funzionino lì. Tocca a te: nel file `BETA_FINAL_HONOR200_TEST_PLAN.md` trovi i passi, nell'ordine, con cosa guardare. Finché non li fai, **non aggiornare l'app principale**.
