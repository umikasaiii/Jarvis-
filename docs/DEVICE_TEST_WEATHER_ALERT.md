# Device test checklist — PASSAGGIO 14.2 Proactive Weather Alert Reliability

Pacchetto di accettazione dispositivo per PASSAGGIO 14.2 (avviso serale
pioggia/temporali per il giorno dopo). Nessuna infrastruttura Robolectric/
instrumented esiste in questo repository — tutto ciò che segue va eseguito
sull'HONOR 200 reale (o un dispositivo/emulatore equivalente).

**Stato atteso legittimo**: `AUTOMATED=PASS, CI=PENDING, DEVICE=PENDING` per
ogni riga finché questa checklist non è stata eseguita davvero — non è un
fallimento, è lo stato onesto di una nuova funzione la cui parte
automatizzata (`:core` + store test) è già verde.

## Identità build

- Commit HEAD al momento della scrittura: vedi `Commit SHA` nel report OUTPUT
  di PASSAGGIO 14.2 in questa stessa conversazione.
- `BuildConfig.BUILD_ID` (Diagnostica › card "Ultima risposta chat") deve
  combaciare con la SHA a 7 caratteri di quel commit prima di iniziare.

## Percorso di test rapido, senza aspettare la pioggia reale

Il pannello Diagnostica › "Meteo — avviso pioggia/temporali" ha, solo su
build debug, tre pulsanti "Sereno"/"Pioggia"/"Temporale" che iniettano un
forecast fittizio NON-PRODUZIONE (mai il meteo reale in cache) e verificano
l'intera pipeline claim→notifica con una chiave di occorrenza
DELIBERATAMENTE distinta (`WEATHER_ALERT_DEBUG:`), quindi non può mai
sopprimere né essere soppressa dalla vera valutazione serale della stessa
notte.

1. Apri Diagnostica › card "Meteo — avviso pioggia/temporali".
2. Tocca "Sereno" — atteso: testo "Nessun avviso da questo scenario", MAI
   una notifica.
3. Tocca "Pioggia" — atteso: una notifica reale "Domani è prevista
   pioggia." compare nello shade.
4. Tocca di nuovo "Pioggia" — atteso: testo "già stato simulato e
   consegnato oggi", NESSUNA seconda notifica (dedup reale verificato).
5. Tocca "Temporale" — atteso: una SECONDA notifica reale (chiave diversa
   dallo scenario pioggia) "Domani sono previsti temporali."

## Scenari reali (richiedono un giorno con vera pioggia/temporale previsti)

6. La sera (19:00-21:00), con «Proattività» attiva e meteo attivo/luogo
   configurato, apri Diagnostica › "Meteo — avviso pioggia/temporali" ›
   "Controlla adesso" — atteso: `stato dati=SUCCESS_DATA`, `pericolo=` il
   valore reale (NO_ALERT/RAIN_EXPECTED/HEAVY_RAIN/THUNDERSTORM) coerente
   con una vera app meteo di riferimento per lo stesso luogo/giorno.
7. Se il pericolo è ≠NO_ALERT: attendi l'evento reale del giorno dopo —
   atteso: **esattamente una** notifica "Domani è prevista pioggia"/
   "Domani sono previsti temporali" arrivata la sera prima, mai una
   seconda anche se controlli Diagnostica più volte la stessa sera.
8. Riavvia il telefono durante la finestra 19-21 dopo che l'avviso è già
   stato consegnato — atteso: nessuna seconda notifica al riavvio
   (l'occorrenza persiste nel DB, non solo in memoria — riusa
   l'architettura PASSAGGIO 14.1).
9. Disattiva temporaneamente la rete durante la finestra serale (prima che
   l'avviso sia stato consegnato) — atteso: "Controlla adesso" mostra
   `stato dati=SOURCE_FAILURE` o `DATA_UNAVAILABLE`, MAI un avviso
   inventato; riattivando la rete entro la stessa finestra (il tick orario
   successivo), l'avviso arriva comunque se il pericolo è reale.
10. Confronta l'ora di "Buongiorno" del mattino successivo — atteso:
    nessuna interferenza fra l'avviso serale e il briefing mattutino
    (occorrenze/chiavi completamente distinte, § `ProactiveOccurrenceKey`).

## Onestà — Honor 200 acceptance

Nessuno degli scenari 1-10 è stato eseguito da questo ambiente (nessun
dispositivo Android disponibile qui). Non dichiarare PASS su nessuna riga
finché non è stata eseguita realmente sul dispositivo.
