# BETA 3/8 — Blocco Appunti, Attività/Promemoria, Archivio personale

Stato: **CI VERIFIED** (compila, test JVM e test su emulatore passano). **NON DEVICE VERIFIED**: nessuna prova fisica su Honor 200 è stata eseguita.

Legenda stati: *Implementato* = nel codice; *CI* = coperto da test automatici passati; *Device* = confermato su Honor 200 (nessuna funzione lo è ancora).

## 1. Stato iniziale
- Branch di partenza `claude/beta02-local-ai-chat`, HEAD `da6f8ce135edf161487ce1ee711dc10f6af3b976` (verificato).
- Nuovo branch `claude/beta03-notes-tasks-archive`. Correzioni BETA 1/2 (database, backup, AI locale) intatte; nessuno schema Room modificato (versione 15); `latest-debug` non toccato (resta `550ca02`, job publish saltato).
- Esistevano già: Archivio con Appunti (Room `archive_items`, cartelle, fissate, checklist, temi/sfondi), liste, documenti; schermata Memoria (note in `JARVIS/Memoria.md`); Attività in stile Google Tasks (`AgendaScreen/ViewModel/Repository`, `ReminderScheduler` con allarmi esatti).

## 2. Componenti riutilizzati
`AgendaScreen`, `AgendaViewModel`, `AgendaRepository`, `ReminderScheduler`; `ArchiveRepository`/`ArchiveDao` (Room); `MemoryIndex`; editor formattato, temi, sfondi, `FormattingToolbar`, `MarkupPreview` e checklist; `FolderChip`/`FolderPickerDialog`/`FolderManagerDialog`; `CommandMatcher`, `ToolRunner` e i tool `*_archive_item`.

## 3. Modifiche
- `:core` (puro, testato): `notes/NotesFeed` (viste, merge, filtro, ordinamento, `NoteSavePolicy`), `notes/NoteCommandParser`, `agenda/DueAlerts`.
- App: `NotesScreen`, `NotesViewModel`, `NoteEditor` (nuovo editor sicuro); Archivio › Appunti ora apre la stessa schermata Note (rimossi `NotesTab` e il vecchio editor); voce «Note» in Home, Impostazioni e Archivio; `DueAlerts` in `AgendaViewModel`; scrittura atomica di `agenda.md`; append e disambiguazione in `update_archive_item`; frasi nominate in `CommandMatcher`.
- CI: il workflow emulatore ora esegue anche `NotesStoreInstrumentedTest`.

## 4. Gestione dei due archivi di note
| Archivio | Contenuto | Proprietario | In «Note» |
|---|---|---|---|
| Archivio (Room `archive_items`, kind NOTE) | note scritte dall'utente | `ArchiveRepository` | sì, modificabili/fissabili |
| Memoria (`JARVIS/Memoria.md`, PERMANENT/SENSITIVE) | ricordi che l'utente vede/modifica in Memoria | `MemoryIndex` | sì, con etichetta «Memoria»; titolo = prima riga; non fissabili; SENSIBILE mostrato mascherato |
| Memoria temporanea della conversazione | stato di lavoro dell'AI | `ConversationMemoryStore` | **mai** mostrata |
| Documenti, liste, da-vedere | — | repository propri | non sono note |

`NotesFeed.merge` crea solo *viste* (id originale + provenienza `ARCHIVE`/`MEMORIA`, nessuna copia, nessuna migrazione). Ogni modifica va al repository proprietario. Una nuova nota è creata sempre in Archivio. L'eliminazione di una cartella sposta solo le note in «Senza categoria».
La schermata Memoria resta per la gestione della memoria dell'assistente (categorie AI, memoria breve, vault); non è stata rimossa.

## 5. Funzioni Note (Implementato; CI per logica e store; Device: no)
Griglia/elenco, creazione, titolo+contenuto, editor a schermo intero, **salvataggio automatico** (~1 s dopo l'ultimo tasto, in pausa app, in uscita; stato visibile «Salvato/Non salvato», l'editor non si chiude se il salvataggio fallisce), titolo vuoto non blocca (derivato dal testo), nota vuota mai creata, salvataggi serializzati con id (niente duplicati né modifica di altra nota), ricerca, cartelle (creare, rinominare, eliminare=disarchiviare, spostare dal menu della nota o dall'editor), fissate in alto (solo note Archivio), checklist selezionabili (anteprima), eliminazione con conferma, temi/sfondi conservati, persistenza.

## 6. Funzioni Attività
Già presenti e riutilizzate: elenco, liste personalizzate, aggiunta rapida, modifica, scadenza data+ora, notifiche locali (allarmi esatti), stella, sottoattività, spostamento tra liste, completa/riapri, sezione Completate, persistenza su `agenda.md`.
Corretto in questo passo: aggiungere o cambiare una scadenza **modificando** un'attività non creava la notifica (solo la creazione lo faceva) → `DueAlerts`; rimossa la data → regole eliminate; regole scelte dall'utente mantenute. Scrittura di `agenda.md` resa atomica. Le attività completate non vengono pianificate e il completamento cancella gli allarmi (codice preesistente, ora pinnato da un test).
**Ricorrenze: non implementate** (rimandate): richiedono un formato retrocompatibile in `Agenda.md` e la ripianificazione degli allarmi senza alterare il calendario esistente.

## 7. Funzioni Archivio
Documenti (importa, apri, rinomina, sposta, elimina, cartelle), Appunti (→ schermata Note), Liste/Spesa/Da vedere, Memoria (sola lettura), TODO (→ Attività), ricerca per cartella, tutto offline. Funzioni esistenti invariate. Nessun nuovo RAG (BETA 4/8).

## 8. Integrazione chat/AI (Implementato; CI per il solo instradamento; esecuzione reale: Device)
| Frase | Strumento |
|---|---|
| «Crea una nota chiamata Idee per Jarvis» | `create_archive_item` (titolo dato) |
| «Aggiungi alla nota Idee per Jarvis: …» | `update_archive_item` con `append` (non sostituisce) |
| «Mostrami le note sulla moto» | `search_archive` |
| «Ricordami domani alle 18 di controllare l'assicurazione» | percorso promemoria esistente (testato che non sia una nota) |
| «Aggiungi comprare il latte alla lista Spesa» | `add_list_item` (testato) |
| «Quali attività devo completare oggi?», «Segna come completata X» | percorso Agenda strutturato preesistente (non modificato) |
Più note corrispondenti: elenca i titoli e chiede quale; un titolo esatto vince sulle menzioni nel corpo. Eliminazione di una nota: conferma già prevista (`CONFIRMING_WRITE`). Nessun secondo sistema di tool-calling. I test verificano la scelta dello strumento e dei suoi argomenti, **non** la comprensione del modello reale né il parlato (STT).
Limite: la ricerca «note» tramite chat copre le note Archivio e le liste, non i ricordi di Memoria (per quelli esiste `search_memory`).

## 9. Test eseguiti
- Core (JVM, locale e CI): 1770 test, 0 fallimenti. Nuovi: `NotesFeedTest`, `NoteCommandParserTest`, `DueAlertsTest`, `TaskLifecycleTest` (creazione, modifica, rilettura, spostamento lista, completa/riapri, sottoattività, nessun duplicato, scadenze/notifiche, righe estranee).
- Android unit test JVM (CI): `CommandMatcherTest` (5 nuovi) e `NotesTasksArchiveGuardTest` (9 scansioni sorgente: raggiungibilità, editor sicuro, nessuna copia fra store, cartelle non distruttive, notifiche, scrittura atomica, nessun cambio Room).
- **Test su emulatore** (workflow «DB migration tests (emulator)», API 34): 20/20 passati, di cui 10 nuovi su Room reale: crea/modifica/rileggi/elimina, modifica non tocca altre note, ricerca, spostamento cartella, persistenza dopo chiusura/riapertura DB, nessun duplicato, merge con Memoria senza copie, nessuna riscrittura se invariato, scrittura su DB chiuso = errore pulito, titolo vuoto rifiutato.
- Compilazione: APK debug, APK isolato, lint, test strumentati compilati, schema Room: tutto verde.
- **Non testati automaticamente:** UI Compose (nessun test Compose scritto; la UI è coperta solo da compilazione e scansioni di sorgente), `ReminderScheduler`/allarmi esatti sul dispositivo, `AgendaRepository` su file, ricerca di Memoria, eliminazione cartelle da UI.

## 10. Problemi ancora aperti
- `Agenda.md` è rigenerato a ogni modifica: testo libero scritto a mano in quel file non viene mantenuto.
- Note di Memoria: niente fissa, titolo solo derivato; credenziali/testi rifiutati dalla memoria non si salvano (messaggio «Non salvato»).
- Home alternativa ARES: Note raggiungibili da Archivio, non da una tessera propria.
- Grafica del nuovo schermo non verificata a vista (tastiera, notch, tema Rouge/Ares).
- Rinomina cartella: note di Memoria passano da `setCategory`, che potrebbe normalizzare il nome.

## 11. Rimandato
Ricorrenze giornaliere/settimanali/mensili; ricerca unificata note+memoria+documenti (BETA 4/8); test Compose; fissa per note di Memoria.

## 12. CI
- CI #513: https://github.com/umikasaiii/Jarvis-/actions/runs/42487940197 (commit `5698fa1`, success)
- Emulatore: https://github.com/umikasaiii/Jarvis-/actions/runs/42487940241 (success, 20/20)

## 13. Commit
- Inizio: `da6f8ce` · codice: `5698fa1` · questo report: commit successivo (solo docs).

## 14. APK isolato (applicationId `com.simone.jarvismobile.debug.beta`, non sovrascrive l'app principale)
- Artifact `app-debug-isolated`: https://github.com/umikasaiii/Jarvis-/actions/runs/42487940197/artifacts/13620930967
- SHA-256 dello **zip** scaricato: `d10ebf128f65437fd88a45234576964fb240dad447be8be351182846b1fe76fb`
- SHA-256 dell'**APK** al suo interno: non estratto da me; calcolalo dopo il download con `sha256sum app-debug.apk` (il report CI `apk-report`, artifact 13620876238, contiene l'identità dell'APK).
- Questo è l'APK da testare: il push del report avvia un'altra CI con un APK diverso.

## 15. Checklist Honor 200 (modalità aereo; annota i risultati reali)
**Note**: creare 3 note · modificarne una (scrivere, aspettare «Salvato») · creare una checklist e spuntarla in Anteprima · creare 2 cartelle e spostare note · cercare una nota · chiudere forzatamente e riaprire: contenuti, cartelle e fissate invariati · modificare una nota e uscire subito con Indietro: testo conservato · eliminare una nota (compare la conferma) · aprire una nota di Memoria e verificare l'etichetta.
**Attività**: 5 attività in liste diverse · scadenza a pochi minuti → arriva UNA notifica · aggiungere una scadenza modificando un'attività senza data → notifica · completare (nessuna notifica) e riaprire · sottoattività · spostare tra liste · chiudere e riaprire: tutto presente.
**Archivio e AI**: aprire un documento e una nota · cercare un elemento · in chat: «Crea una nota chiamata Prova», «Aggiungi alla nota Prova: testo», «Mostrami le note sulla prova», «Ricordami tra 5 minuti di …» · creare due note con titolo simile e verificare che JARVIS chieda quale · RAM/crash.
Stato dopo l'esito: DEVICE VERIFIED solo se i punti sopra sono confermati da te.
