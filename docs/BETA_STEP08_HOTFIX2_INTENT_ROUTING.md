# BETA 8 hotfix 2 — falsi comandi telefonici e routing conversazionale

**Stato: CI VERIFIED** (JVM, test Android, emulatore Android 14 — modello assente/simulato). **NON DEVICE VERIFIED**: la risposta reale di Gemma 3 1B (LiteRT-LM) sull'Honor 200 non è stata provata da me.

## Sintomo (Honor 200, build `cd3f034`, Gemma 3 1B INT4 «Pronto» nello slot Rapido)
«Quale dei due conserva i dati anche quando spengo il telefono?» e «Ricorda che il mio animale immaginario si chiama Nebulix. Come si chiama?» ricevevano entrambe «Quale numero devo preparare nel dialer?».

## Diagnosi (evidenza, non ipotesi)
Riprodotto eseguendo il **vero `CommandMatcher`** su JVM (copia temporanea in `:core`, poi eliminata; l'app compila solo in CI):

| Frase | Percorso prima |
|---|---|
| Quale dei due conserva i dati… spengo il telefono? | `Match.Ask(prepare_call/number)` |
| Ricorda che… si chiama Nebulix. Come si chiama? | `Match.Ask(prepare_call/number)` |
| Come si chiama la capitale della Francia? / Quanta RAM ha il telefono? / Il mio telefono è lento… | `Match.Ask(prepare_call/number)` |
| Scrivi un messaggio di auguri per mia madre | `Match.Ask(compose_sms/number)` |

1. **Causa principale — deterministica, prima del modello:** `CommandMatcher.dialDraftCall` (eseguito prima del classificatore in `match()`) usava `DIAL_REQUEST_RE`, una regex **non ancorata** che scattava su `chiama` e `telefon\w*` in qualunque punto: «si **chiama**», «il **telefono**». Il classificatore (`LlmIntentClassifier`) e Gemma **non erano coinvolti** in quel percorso. Stessa famiglia di difetto per gli SMS («scrivi un **messaggio**…»).
2. **Aggravante — domanda in sospeso:** dopo `Ask`, `pendingSlot` catturava il messaggio successivo; `fillSlot("prepare_call")` ricostruiva «chiama $risposta» e, senza numero, ripeteva la stessa domanda (e altri slot — navigazione, media, vault — avrebbero trasformato una frase intera in destinazione/ricerca).
3. **Difetto latente nel classificatore (confermato dal codice, non causa del caso riportato):** per `prepare_call`, `compose_sms`, `navigate`, `open_app`, `play_media`, `search_vault`, `forget/update_memory`, `list_notifications`, `create_calendar_event`, se il builder deterministico non trovava nulla il codice **inventava** una domanda (o un `Run` per le notifiche) dal solo nome dell'intento: «model output = authorization» (Master §4.2). `ToolIntentGate` lo avvicinava a frasi con «telefono»/«ricorda».
4. **Confidenza 0,72 / soglia 0,58:** esaminate; 0,72 è il valore se il modello dà un nome senza punteggio e supera la soglia, ma **non è la causa** del caso (il classificatore non veniva raggiunto). Non modificate; l'accesso ai comandi sensibili ora non dipende dal punteggio.
5. **Contaminazione tra turni:** `lastAskedSlot` non veniva azzerato da «Nuova conversazione» (lo faceva solo `chatReply`); `pendingSlot`, `pendingConfirmation` sì. Corretto.
6. **Traccia del percorso:** prima il turno non diceva quale ramo l'aveva gestito. Ora sì (sotto).

## Correzione (rispetta il Master: nessun nuovo router generale)
- **Grammatica di hard-command ancorata** (`core/tools/PhoneCommandGrammar`, Master §11 «grammar strettissime di hard commands»): la chiamata deve **aprirsi** con `chiama/chiamare/telefona/telefonare` (dopo al più saluto/«per favore»), `prepara una chiamata…`, `apri il dialer`. L'SMS deve aprirsi con un verbo di invio (o «sms») **e** nominare un SMS o contenere un numero. «Mi/Ti telefono…» (clitico) è una frase, non un comando. `CommandMatcher` delega alla grammatica; i numeri vengono estratti solo dalle parole dell'utente (`PhoneNumbers`).
- **Il classificatore non autorizza (§4.2):** per gli intenti sensibili il nome instrada al builder deterministico; senza evidenza nel turno corrente la proposta decade (→ conversazione). Un call/SMS «per parafrasi» è accettato solo con **numero + vocabolario coerente nella stessa richiesta** (`modelProposedDial/Sms`). Aggiunti 3 esempi negativi al prompt (Master §11.4).
- **Domande in sospeso (§4.1/§10.2):** `SlotAnswerPolicy` — la risposta entra nello slot solo se ha la **forma** del valore (numero di telefono per chiamata/SMS; non una domanda; non una frase lunga per destinazione/ricerca), entro 120 s, e la stessa domanda non si ripete; altrimenti la domanda decade e il messaggio prosegue come richiesta indipendente. «Nuova conversazione» azzera anche `lastAskedSlot`.
- **Diagnostica per turno:** `route=deterministic:<tool>` · `classifier` · `pending:<tool>` · `pending-dropped:<tool>/<slot> (not_an_answer|expired|same_question)` · `conversation`, in `diagnostic` e nel log (`Log.i`), senza testo dell'utente.
- **Non toccato:** nessun quarto modello, motore Classico non rimosso, motori non unificati (A3 dopo i gate semantici), A1 non avviata, `latest-debug`, app principale, branch canonico, dati utente.

## Test (sempre distinguendo simulato da reale)
- **Core JVM: 1971 test, 0 fallimenti** — `PhoneCommandGrammarTest`, `SlotAnswerPolicyTest` (frasi riportate, chiamate vere, SMS, evidenza del modello, forma delle risposte, TTL, domanda ripetuta).
- **Regressione sul codice reale:** i 54 test esistenti di `CommandMatcherTest` passano con il matcher modificato (eseguiti su JVM con copia temporanea; poi in CI).
- **Android unit JVM (CI #536, 489+ test, verde) — modello FINTO:** `FalseIntentRegressionTest` — RAM/SSD su due turni, Nebulix e domanda di richiamo, saluto, «Chiama il numero 3331234567» → `prepare_call` col numero dell'utente (il tool apre solo il dialer con `ACTION_DIAL`, politica `EXTERNAL_COMMUNICATION`, conferma prima; nessun `ACTION_CALL`), `prepare_call|99` e tutti gli altri intenti sensibili con punteggio alto **e** senza punteggio → nessun comando, parafrasi con numero → comando, conversazione libera dopo un'azione chiesta dal modello, nessuna domanda inventata nel classificatore, reset e pending (scansione strutturale), traccia senza testo utente.
- **Emulatore Android 14 (run 42827026901, success, sul commit di codice `5515c64`) — nessun modello caricato:** `ConversationRoutingInstrumentedTest` sul vero `SessionCoordinator`: sequenza riportata senza risposte telefoniche; «Chiama il numero…» → solo conferma del dialer; «Chiama Marco» poi domanda indipendente → la domanda in sospeso decade; «Chiama Marco» poi «3331234567» → conferma; «Nuova conversazione» con domanda pendente; stessa domanda mai due volte. Con modello assente la chat risponde «modello non disponibile». Più `ParserInitInstrumentedTest` (regex di BETA 8 hotfix 1) e le altre classi strumentate.
- **Cosa NON dimostrano:** né l'emulatore né i test JVM eseguono LiteRT-LM/Gemma. «Una normale domanda raggiunge il motore» è provato come *routing* (nessun parser/matcher la cattura; con modello assente arriva al messaggio onesto), non come inferenza reale.

## Limiti noti
- «Ricorda che X. <domanda>?» in un solo messaggio salva il fatto (comando di memoria) ma non risponde alla domanda finale nello stesso turno; candidato per A1/A3.
- `ToolIntentGate` (esistente) usa ancora parole come «telefono/ricorda» per decidere se interpellare il classificatore: ora innocuo per i comandi sensibili (serve evidenza), ma resta un gate a parole chiave da superare con il lavoro semantico.
- Gli intenti non sensibili del classificatore (timer, sveglia, ricordo, promemoria) mantengono le loro domande di completamento.

## APK da installare (sopra la Beta esistente)
Build di riferimento = commit di codice **`5515c6469e3f04ba1fcec0e786190ebf4a48cee9`** (CI #536, run 42824431619, publish **SKIPPED**).
- Artifact `app-debug-isolated` — ID 13768712447 — https://github.com/umikasaiii/Jarvis-/actions/runs/42824431619/artifacts/13768712447 — scade il 2027-02-08T13:25:48Z (serve il login GitHub; è uno ZIP che contiene l'APK)
- Digest dello **ZIP**: `sha256:745ecb8955844b71bd22454a7d8eb8692df82a1b9860184c40757b43777dcf28`
- **SHA-256 dell'APK** (calcolato in CI con `sha256sum`): `d3b4a99586a755efc7ca77b201d3592f0c986b3df4a0011ef6381d04cd7c6e3c`
- 217.803.122 byte · `com.simone.jarvismobile.debug.beta` · versionCode 1 / versionName 0.1.0 · BUILD_ID `5515c64` · minSdk 31, targetSdk 35 · `arm64-v8a` · `debuggable` · certificato `CN=Android Debug` SHA-256 `33018d7f…c8fb` (invariato: si installa sopra la Beta esistente senza cancellare dati; non provato su telefono).
- Sostituisce `cd3f034`. Un commit successivo di sola documentazione può generare un'altra build: il riferimento resta `5515c64`.
- `latest-debug` invariato (`550ca02`); nessuna release, nessun merge.

## Da provare sull'Honor 200 (tuo test reale — il problema NON è dichiarato risolto lì)
1. Installa sopra; ID build `5515c64`; Gemma 3 1B nello slot Rapido «Pronto».
2. «Qual è la differenza tra RAM e SSD?» poi «Quale dei due conserva i dati anche quando spengo il telefono?» → risposte del modello, **niente** richiesta di numero.
3. «Ricorda che il mio animale immaginario si chiama Nebulix.» poi «Come si chiama il mio animale immaginario?» → niente dialer; (la risposta «Nebulix» dipende dal modello/dalla memoria: annota cosa dice).
4. «Chiama il numero 3331234567» → chiede conferma e, solo dopo il tuo sì, apre il dialer **senza avviare la chiamata**. «Chiama Marco» → chiede il numero; poi scrivi una domanda qualunque → non ripete la richiesta.
5. Apri la Diagnostica/riga di stato e annota il valore `route=…` per ogni turno; se vedi un errore diverso, mandami il testo esatto.
