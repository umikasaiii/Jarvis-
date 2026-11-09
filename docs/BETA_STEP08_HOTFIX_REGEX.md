# BETA 8 hotfix — la chat non rispondeva («errore tecnico: …OfflineHubParser»)

**Stato: CI VERIFIED (emulatore Android 14 incluso). NON DEVICE VERIFIED.** Nessuna prova su Honor 200; non è provato che il modello Gemma/LiteRT-LM risponda.

## Sintomo
Sull'Honor 200, build `900c0e3`, a «Ciao Jarvis, presentati brevemente in italiano» la chat rispondeva «Non sono riuscito a completare la risposta (errore tecnico: com.simone.jarvismobile.core.offlinehub.OfflineHubParser)».

## Causa (dimostrata su runtime Android, non dedotta)
Un test diagnostico temporaneo, eseguito sull'emulatore (Android 14, run 42712887185), ha dato:
- `Regex("(?iU)ciao")` → `PatternSyntaxException: Invalid value for match mode flags`; `(?U)` → «Syntax error in regexp pattern». Il motore regex di Android (ICU) **non accetta il flag inline `U`**. `(?iu)` e `(?i)` sì.
- `OfflineHubParser.parse(...)` → `ExceptionInInitializerError` ← `PatternSyntaxException`; la chiamata successiva → `NoClassDefFoundError: com.simone.jarvismobile.core.offlinehub.OfflineHubParser` ← `ExceptionInInitializerError`. Il messaggio mostrato in chat è il solo testo di quest'ultima eccezione (`error.message`), cioè il nome della classe.
- Distinzione richiesta: **`PatternSyntaxException`** = causa radice; **`ExceptionInInitializerError`** = come si manifesta la prima volta (l'inizializzatore statico dell'`object` fallisce); **`NoClassDefFoundError`** = ogni uso successivo (ART ricorda la classe come fallita). **Non è un problema di packaging**: la classe è nell'APK.
- Il difetto non riguardava solo l'hub. Lo stesso errore, con la stessa causa, colpiva tutti gli altri parser che la chat consulta prima del modello: `DocumentQueryParser`, `StoredFactQuestion`, `NoteCommandParser`, `PassageSanitizer`/`SourceCitations` (in totale 40 espressioni in 5 file di `:core`). Correggere solo `OfflineHubParser` avrebbe spostato il crash sul parser successivo.
- I test JVM non lo vedevano: Java accetta `U` (UNICODE_CHARACTER_CLASS).

## Correzione (branch `claude/beta08-fix-offlinehub-regex`, codice al commit `cd3f0349f816d946fe6bef04ada0c031a59b245d`)
- Nuovo `core/regex/PortableRegex.of(pattern)`: compila l'espressione **identica** a prima; se il motore la rifiuta (`PatternSyntaxException`) riprova con il gruppo di flag iniziale `U`→`u` (`(?iU)`→`(?iu)`). Sulla JVM il comportamento resta quello originale; su Android le classi `\b \w \s \p{L}` e il confronto senza maiuscole sono già Unicode (verificato sull'emulatore con «perché», «PERCHÉ», `Ü/ü`).
- Le 40 espressioni passano da `PortableRegex.of`; il testo dei pattern non è cambiato, quindi i comandi Kiwix e Organic Maps sono invariati.
- Nessun'altra modifica al comportamento dell'app.

## Test
- **Core JVM: 1959 test, 0 fallimenti** — nuovi: `PortableRegexTest` (mappatura dei flag, nessun flag duplicato, errori veri ancora rumorosi, **scansione che vieta nel codice `Regex("(?…U")` grezzi**) e `OrdinaryQuestionsReachTheModelTest` (8 domande normali non sono catturate da nessun parser; i comandi offline funzionano ancora).
- **Emulatore Android 14** (run 42717140075, success; `ParserInitInstrumentedTest`): documenta che l'emulatore rifiuta `(?iU)`; inizializza ogni parser su runtime reale; le domande normali non sono catturate; comandi Kiwix/Organic Maps ancora riconosciuti (apri Kiwix, apri Organic Maps, navigatore offline, cerca farmacia, coordinate, percorso, ricerca Kiwix, domanda ZIM); parser di note/documenti/memoria; italiano con accenti; e **un turno di chat reale** (`SessionCoordinator.processQueuedText` con il grafo Hilt completo): la risposta non contiene «errore tecnico» né `OfflineHubParser`, e «Apri Kiwix» riceve la risposta dell'hub (senza Kiwix installato).
- Android unit JVM, lint, schema Room, compilazione test strumentati, PS 5.1: verdi (CI #533, run 42717140113). Job di pubblicazione: **SKIPPED**.
- Il primo run emulatore (4e80747) aveva 49/50: tutti i test del bug verdi; falliva un test di notifiche di BETA 7/8 (lettura troppo rapida della tendina, notify è asincrono). Corretto con attesa fino a 5 s in `cd3f034`; ora 100% verde.

## Cosa NON è provato
- Il modello **non** è stato dichiarato incompatibile e **non** è stato testato: in CI non esiste un `.litertlm`. L'emulatore prova che la domanda **non si ferma più prima del modello** (nessun parser la cattura, nessun errore tecnico); con modello non caricato risponde il messaggio onesto di modello non disponibile. Che **Gemma risponda** va verificato sull'Honor 200 (caricamento reale, RAM, latenza).
- Nessuna prova su Honor 200 per tutto il resto (vedi `BETA_FINAL_HONOR200_TEST_PLAN.md`).

## APK da usare (build di riferimento = commit di codice `cd3f034`)
- Commit: `cd3f0349f816d946fe6bef04ada0c031a59b245d` · run CI #533: https://github.com/umikasaiii/Jarvis-/actions/runs/42717140113
- Artifact `app-debug-isolated` (ID 13719546199, 97.329.154 byte) — scade il 2027-02-07T23:02:41Z
  Link: https://github.com/umikasaiii/Jarvis-/actions/runs/42717140113/artifacts/13719546199
- Digest dello **ZIP**: `sha256:78f51731aee6ea90a71ffb6511766c7ae09fab7fa04dcd9a7e8e7c9b83b4189e`
- **SHA-256 dell'APK** (calcolato in CI con `sha256sum`): `01c06a3ca655c4d384613843b73b466767b0a77fbb0cf0d7ccc22cafd4f15fe7`
- 217.786.738 byte · `com.simone.jarvismobile.debug.beta` · versionCode 1 / versionName 0.1.0 · BUILD_ID `cd3f034` · minSdk 31, targetSdk 35 · `arm64-v8a` · `debuggable` · certificato `CN=Android Debug` SHA-256 `33018d7f…c8fb` (invariato: stesso certificato e stesso Application ID della build precedente, quindi si installa **sopra** `900c0e3` senza perdere dati; non provato su telefono).
- Questa build **sostituisce** quella di `900c0e3` (`736f53ad…9248`), che contiene il difetto. Un commit successivo solo-documentazione può generare un'altra build: il riferimento resta `cd3f034`.
- `latest-debug` invariato (`550ca02`), nessuna release, nessun merge.

## Da fare sul telefono
1. Installa sopra la Beta isolata; controlla che l'ID build sia `cd3f034`.
2. Carica il modello `.litertlm`; scrivi «Ciao Jarvis, presentati brevemente in italiano». Atteso: risposta generata dal modello (se non c'è modello: messaggio «modello non disponibile», **non** «errore tecnico»).
3. Prova «Apri Kiwix» / «Cerca una farmacia su Organic Maps» (devono andare all'hub) e una nota/un documento (devono funzionare, prima erano rotti allo stesso modo).
Se la risposta del modello fallisce con un errore diverso, mandami il testo esatto: sarà un problema diverso da questo.
