# Device test checklist — PASSAGGIO 14.1 Morning Briefing Delivery Reliability

Pacchetto di accettazione dispositivo per PASSAGGIO 14.1 (effectively-once
delivery del briefing mattutino). Nessuna infrastruttura Robolectric/
instrumented esiste in questo repository — tutto ciò che segue va eseguito
sull'HONOR 200 reale (o un dispositivo/emulatore equivalente).

**Stato atteso legittimo**: `AUTOMATED=PASS, CI=PENDING, DEVICE=PENDING` per
ogni riga finché questa checklist non è stata eseguita davvero — non è un
fallimento, è lo stato onesto di un fix la cui parte automatizzata (core +
store JVM test) è già verde.

## Identità build

- Commit HEAD al momento della scrittura: vedi `Commit SHA` nel report OUTPUT
  di PASSAGGIO 14.1 in questa stessa conversazione.
- `BuildConfig.BUILD_ID` (Diagnostica › card "Ultima risposta chat") deve
  combaciare con la SHA a 7 caratteri di quel commit prima di iniziare.

## Come annotare ogni passo

Per ogni scenario sotto: apri Diagnostica prima e dopo (pannello "Proattività
(briefing)", già esistente — mostra l'ultimo esito di `run()`; nessuna nuova
UI aggiunta in questo passaggio per l'occorrenza — l'unico segnale
osservabile in-app resta questo pannello + il numero di notifiche mattutine
effettivamente comparse). Segna PASS/FAIL/PENDING per ogni riga.

---

## A — Mattina normale

1. «Automazioni in background» attivo, sveglia impostata dopo le 5:00.
2. Sblocca il telefono al mattino come al solito.
3. Atteso: **esattamente UNA** notifica "Buongiorno" con emoji meteo, nessun
   duplicato nei minuti/ore successive (incluso il refresh +10/+60min: quello
   deve **aggiornare la stessa notifica**, mai aggiungerne una seconda).

## B — Apertura app vicino all'orario pianificato

4. Poco prima dell'orario configurato (`morningBriefingHour`/`Minute`,
   default 8:00) o dell'orario next-alarm+offset, apri l'app manualmente
   (questo NON è un vero sblocco-schermo, ma può comunque far scattare un
   trigger periodico/di sistema).
5. Atteso: nessuna consegna doppia — se il briefing era già stato consegnato
   da un trigger a orario, l'apertura app non ne genera un secondo.

## C — Primo sblocco vicino all'orario pianificato

6. Vicino all'orario CONFIGURED_TIME o NEXT_ALARM, sblocca il telefono per la
   prima volta della giornata (vero `ACTION_USER_PRESENT`).
7. Atteso: **una sola** consegna, indipendentemente da quale dei due trigger
   (l'alarm a orario o lo sblocco reale) arriva per primo — l'altro deve
   vedere `AlreadyOwned` e non fare nulla.

## D — Kill/relaunch dell'app

8. Subito dopo la consegna del mattino, forza la chiusura dell'app (kill dal
   task switcher) e riaprila.
9. Atteso: nessuna nuova consegna al riavvio — l'occorrenza è già `DELIVERED`
   e persiste nel DB, non nella sola memoria di processo.

## E — Reboot prima dell'orario pianificato

10. Riavvia il telefono PRIMA dell'orario configurato/next-alarm di quel
    giorno.
11. Atteso: dopo il boot, gli alarm/i trigger vengono ri-registrati
    (`scheduleAll()`/boot receiver esistenti) e il briefing arriva comunque
    **una sola volta** all'orario giusto — nessuna doppia registrazione del
    worker/alarm che produce due consegne.

## F — Fallimento temporaneo e retry

12. Se possibile riprodurre un fallimento a metà pipeline (es. disattivare
    momentaneamente la rete durante la generazione, se il contenuto dipende
    da un fetch remoto) durante la finestra di generazione del briefing.
13. Atteso: un fallimento pre-consegna rilascia il claim come
    `FAILED_RETRYABLE` (mai bloccato permanentemente) — un trigger
    successivo (es. il refresh +10/+60min, o un nuovo sblocco) può
    completare la consegna; MAI due consegne per lo stesso fallimento.

## G — Attesa 45 minuti, nessun duplicato ritardato

14. Dopo la consegna del mattino, aspetta ~45 minuti senza toccare il
    telefono (finestra che copre sia il refresh +10min sia buona parte del
    percorso verso il +60min).
15. Atteso: nessuna seconda notifica "Buongiorno" separata — il refresh
    +10/+60min aggiorna silenziosamente la STESSA notifica (stesso id
    stabile) con dati freschi, senza mai ri-parlare il briefing né crearne
    una seconda voce visibile nello shade.

---

## Onestà — Honor 200 acceptance

Nessuno degli scenari A-G è stato eseguito da questo ambiente (nessun
dispositivo Android disponibile qui). Non dichiarare PASS su nessuna riga
finché non è stata eseguita realmente sul dispositivo.
