# Device test checklist — PASSAGGIO 12 SEGNALE P0 Foundation

Nessuna infrastruttura Robolectric/instrumented esiste in questo repository
(vedi `CLAUDE.md`), quindi il comportamento reale su schermo di font
scaling/inset/TalkBack/reduced motion non è certificabile da questo
ambiente — solo lo strato puro (`core/segnale/`, `cd core && ./gradlew
test`) e la compilazione (`:app:assembleDebug`/`:app:testDebugUnitTest`/
`:app:lintDebug`, CI Android) lo sono. Questo file è la checklist minima da
eseguire sull'HONOR 200 reale prima di dichiarare **P0 DEVICE ACCEPTANCE
✅** — PASSAGGIO 12 non è "SEGNALE completo"/"redesign UI completo"/
"cinematic UI completo" in nessun caso, solo la fondazione P0 (§Z).

## Identità build

Installare esattamente l'APK prodotto dal run CI di questo push. Annotare
la SHA a 7 caratteri mostrata da `BuildConfig.BUILD_ID` (Diagnostica ›
card "Ultima risposta chat", § fase 6n-audit) e confermare che combacia col
commit di questo passaggio prima di procedere.

## Cosa verificare

1. Installare esattamente l'APK CI di questo commit.
2. Registrare SHA/identità build (vedi sopra).
3. Aprire Diagnostica (la sola superficie migrata in questo passaggio, §N)
   — verificare che la card "SEGNALE (visuale)" mostri
   `foundationVersion=P0-1 profilo=STANDARD motoRidotto=<bool>` e
   `fontPrimario=fallback (system, gate pending)`.
4. Eseguire almeno un turno che produca `toolOutcomeStatuses` (es. "che
   tempo fa oggi?", "quante ore ho dormito?") e verificare che la card
   "Motore conversazionale (debug)" mostri i chip di stato (icona+testo
   colorato) al posto della vecchia riga `esitiTool=...` — nessun crash,
   nessun testo grezzo mostrato.
5. Verificare nessun clipping a scala font normale (1.0x) sui nuovi chip e
   sulla card SEGNALE.
6. Impostare la scala font di sistema a 1.3x, riaprire Diagnostica —
   verificare nessun testo tagliato in quella card/i nuovi chip.
7. Impostare la scala font di sistema a 2.0x, ripetere — stesso controllo.
8. Verificare navigazione gesture/inset di sistema — Diagnostica non deve
   sovrapporsi alla barra di stato/notifiche (nessuna riga di questo
   pass usa `SegnaleScene`/`segnaleSafeContent()` ancora, essendo un'
   estensione della schermata Diagnostica esistente — questo passo
   verifica che l'estensione non abbia rotto l'inset handling già presente
   in quella schermata).
9. Aprire la tastiera in un punto qualunque dell'app (es. campo ricerca) —
   verificare che il layout esistente non sia stato alterato da questo
   passaggio (nessuna schermata di produzione oltre Diagnostica è stata
   toccata, §U).
10. Con TalkBack attivo, navigare sui nuovi chip di stato in Diagnostica —
    ogni chip deve annunciarsi UNA volta con una frase che nomina lo STATO
    ("Stato: dati disponibili" ecc.), mai "icona"/tre annunci separati per
    lo stesso chip.
11. Attivare "Rimuovi animazioni" nelle opzioni sviluppatore/accessibilità
    di sistema — verificare che `motoRidotto=true` compaia nella card
    SEGNALE al prossimo apri-schermo.
12. Con "Rimuovi animazioni" attivo, verificare che nessuna funzione reale
    dell'app smetta di funzionare (§K: "never remove functional feedback")
    — solo il pass di questo giro non introduce ancora alcuna animazione
    ornamentale reale da poter osservare spenta (§O — nessun effetto
    grafico pesante in P0), quindi questo passo verifica principalmente
    l'assenza di regressioni, non un cambiamento visibile.
13. Ruotare il dispositivo/cambiare finestra se supportato — nessuna
    regressione sulla schermata Diagnostica.
14. Scroll ripetuto della schermata Diagnostica (contiene già molte card) —
    nessun jank percepibile introdotto dai nuovi chip.
15. Confermare nessuna regressione visibile o funzionale altrove nell'app
    (Home/Chat/Agenda/Archivio/Navigazione — nessuno di questi è stato
    toccato da questo passaggio, § pre-audit + §U).

## Matrice visiva per i gate SEGNALE futuri (preparata, non eseguita qui)

Larghezza: 360dp / 390dp / 412dp.
Scala font: 1.0x / 1.3x / 2.0x.
Stati: normal / loading / empty / stale / permission missing / error /
partial / offline / unknown action (dove applicabile).

P0 non richiede ancora la certificazione visiva completa su questa
matrice (§X) — la checklist sopra (15 punti) è il gate minimo di questo
passaggio.
