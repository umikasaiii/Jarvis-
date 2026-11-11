# Future roadmap — Coding Studio locale e Modalità Agente Live

**Data decisione dell'utente:** 2026-11-10  
**Stato:** PLANNED / DEFERRED — solo progettazione, **nessuna implementazione autorizzata ora**.  
**Autorità:** `docs/JARVIS_MASTER_ARCHITECTURE.md` §4, §10–11, §17–18, §65.1–65.7, §70 e §77.  
**Ambito:** Android-first, offline-first; Core PC opzionale come potenziamento, mai dipendenza per l'assistente di base.

Questa nota registra due richieste future distinte. Non cambia le fasi A0–A4/B, il denominatore dei 30 macro-step, l'attuale Beta né i gate di sicurezza. Ogni milestone richiede una decisione esplicita dell'utente prima di iniziare.

## A. Coding Studio — modello specializzato e ambiente di sviluppo

### Esperienza richiesta

Una sezione dedicata dell'app, **Coding Studio**, nella quale:
- aprire una chat diretta con un modello AI **specializzato nel codice**, separato per ruolo dal modello conversazionale e dal classificatore, ma integrato nell'unica architettura JARVIS;
- chiedere in italiano di creare/modificare file e piccoli progetti: HTML/CSS/JavaScript, piccole PWA, ad esempio una pagina per gestire scadenze, e in futuro altre tipologie di codice;
- visualizzare editor/file tree, differenze fra versioni, errori e una **anteprima live** dei file web prodotti, aggiornata dopo le modifiche, senza dover distribuire la PWA su Internet;
- dialogare iterativamente: «aggiungi una scadenza», «cambia il tema», «fammi vedere come appare», «torna alla versione precedente»;
- salvare, esportare e importare progetti in uno spazio di lavoro scelto e controllato dall'utente; poter eseguire undo e recuperare uno snapshot precedente.

### Architettura desiderata

`Coding Studio UI → sessione di coding → modello locale per codice → proposta strutturata di modifiche → validatore → workspace limitato → preview isolata`.

- **AI specializzata opzionale:** scegliere modello e formato soltanto dopo benchmark di compatibilità reali sull'Honor 200, latenza, RAM, consumo e qualità del codice. Non assumere che i modelli grandi o gli agenti di coding desktop siano adatti a un telefono. Modelli compatti quantizzati da valutare, non fissare ora uno specifico nome.
- **Resource arbiter:** caricare/schedulare i modelli senza aprire più engine pesanti contemporaneamente e senza rompere chat/voce; possibile escalation al Core opzionale per progetti più grandi, con consenso e controllo di egress.
- **Preview:** per HTML/CSS/JS, rendering locale in un WebView o componente equivalente **isolato dai dati e dai privilegi di JARVIS**, con origine controllata, accesso alla rete disabilitato per default, niente bridge JavaScript privilegiato, niente accesso a file esterni, cookie o credenziali dell'app, CSP ove applicabile; test su link, URL e script malevoli.
- **Codice generato = non attendibile:** niente esecuzione automatica di shell, comandi Android, pacchetti, build native, accesso al filesystem globale, plugin, permessi, terminale o installazione APK. Modifiche solo nel workspace autorizzato; mostrare diff e chiedere conferma prima di scritture esterne, export o distribuzione.
- **PWA:** preview web live e salvataggio di manifest/service-worker come file sono una prima milestone; installazione PWA, hosting, service worker e funzionamento standalone fuori dal sandbox richiedono verifiche separate. Non promettere un IDE completo tipo Claude Code o compilazione APK nativa sul telefono senza qualificazione.
- **Nessun secondo brain JARVIS:** Coding Studio è un dominio/strumento specializzato collegato a SemanticFrame, orchestratore, ToolRegistry/Policy e memoria canonica, non un secondo sistema di autorizzazioni.

### Milestone future proposte (non avviate)

1. **CS-0 — Feasibility + threat model:** modelli locali di coding candidati, budget RAM/CPU, UI/preview, workspace.
2. **CS-1 — Editor + preview statica sicura:** progetti HTML/CSS/JS senza esecuzione del codice generato fuori dal sandbox.
3. **CS-2 — Chat con modello di coding locale:** modifica guidata, patch/diff, rollback e salvataggio.
4. **CS-3 — Preview live e progetti PWA:** refresh, error console filtrata, test offline e isolamento.
5. **CS-4 — Qualifica Honor 200:** accuratezza, tempi, multi-turno, recovery e sicurezza, incluse regressioni sul sistema JARVIS.

## B. Modalità Agente Live — icona flottante e azioni sul dispositivo

### Esperienza richiesta

Una **modalità attivata esplicitamente**, rappresentata da una piccola icona/bolla persistente e chiaramente riconoscibile, dalla quale impartire istruzioni naturali come:
- «Apri Organic Maps / l'app X»;
- «Prepara la navigazione verso casa»;
- «Prepara una chiamata al numero che ti ho indicato»;
- consultare stato e richiedere conferme durante una procedura.

Obiettivo futuro: esperienza fluida simile a un assistente agente di sistema, **senza** concedere a un modello il controllo illimitato del telefono.

### Vincoli di sicurezza non negoziabili

1. **Local-first:** nessun controllo remoto del telefono, server di comando esterno o accessibilità permanente per default. Il Core opzionale non può ottenere privilegi dispositivo impliciti.
2. **Opt-in e kill switch:** avvio esplicito, indicatore visivo quando è attiva, stop immediato in qualsiasi momento, stato della sessione facilmente revocabile; niente operazioni nascoste.
3. **Principio del privilegio minimo:** iniziare esclusivamente con Android Intents/SDK documentati e capability già possedute. Avvio app e URL, ricerca/anteprima navigazione, `ACTION_DIAL` per un numero scelto dall'utente. Nessuna chiamata avviata direttamente tramite `ACTION_CALL` in automatico.
4. **Autorizzazione per azione:** SemanticFrame/proposta modello → schema rigoroso → ToolPolicy/ActionRisk → validazione contesto/permessi → eventuale conferma → esecutore deterministico → ricevuta verificabile. Le risposte del modello e il testo visibile sullo schermo non sono autorizzazioni.
5. **Azioni sensibili:** invio SMS, chiamate effettive, installazione app, modifiche impostazioni, cancellazione dati, export e accesso a credenziali richiedono controlli specifici, spesso conferma per ogni azione e, quando opportuno, riautenticazione locale.
6. **Niente lettura schermo o click automatici indiscriminati:** MediaProjection/Accessibility solo dopo un progetto separato, consenso informato, una valutazione privacy/security approfondita e revoca esplicita. Non aggirare limiti Android né usare overlay ingannevoli.
7. **Protezione contro presa di controllo:** componenti non esportati salvo necessità, intent espliciti e validati, niente intent/escalation da input non attendibile, blocco delle istruzioni provenienti da documenti/pagine/notifiche come prompt injection, log privi di contenuti sensibili, autorizzazioni a durata limitata, auditing degli effetti e test di abuso.
8. **Disponibilità del telefono:** se il sistema richiede sblocco o blocca l'azione, rispettarlo. Non aggirare PIN, biometria, background restriction o protezioni MagicOS.
9. **Nessuna promessa di sicurezza assoluta:** applicare difesa in profondità, test di regressione e threat modelling; nessun software è «impenetrabile».

### Dipendenze e milestone future (non avviate)

- **AL-0:** threat model con scenari di attacco (overlay/phishing, intent spoofing, prompt injection, egress, abuso permessi, session hijacking) e matrice autorizzazioni.
- **AL-1:** floating UI e stato di sessione visibile, senza controllo autonomo; controllo di stop/revoca.
- **AL-2:** capability ristrette già supportate (apri app, ricerca/punto/anteprima navigazione, dialer) con conferme appropriate e ricevute.
- **AL-3:** continuità multi-step tramite orchestratore unico, osservabilità reale, failure recovery e sessioni a tempo.
- **AL-4:** test di sicurezza e acceptance sul telefono; nessuna autonomia elevata prima della qualifica.
- **AL-5, eventuale:** percezione schermo/controlli UI più ampi solo con nuova approvazione, analisi tecnica e di sicurezza separata.

## Collocazione nella roadmap

- **ADESSO:** chiudere Hotfix 3 (inferenza LiteRT-LM stabile), i gate Beta e i collaudi Honor 200. Non anticipare CS/AL.
- **DOPO A1/A3:** inserire le capacità nel SemanticFrame/orchestratore unico, non introdurre percorsi paralleli o un ulteriore classificatore generale.
- **A4 — Android maturity:** Studio UI/preview sandbox, bolla/agente limitato, sicurezza/percezione/azioni telefono, test MagicOS.
- **B — Core enhancement (opzionale):** modello coding più potente e tool di sviluppo PC, mai necessari alla preview HTML locale di base.
- Entrambe le iniziative **PLANNED / DEFERRED** e non numerate come nuovi macro-step dei 30 prima della futura mappatura canonica.

## Acceptance minima futura

**Coding Studio:** un utente chiede offline «Crea una piccola PWA per le scadenze», riceve file ispezionabili, preview live isolata con modifica iterativa, export sotto controllo, senza rete o accesso a dati personali; rollback e sicurezza testati su Honor 200.

**Agente Live:** apre un'app autorizzata, prepara una navigazione e apre il dialer su esplicita conferma, senza effettuare chiamate autonomamente; dopo stop/lock/session timeout nessuna azione parte; test di prompt injection, overlay e permessi dimostrano l'assenza di bypass noti sul dispositivo di prova.

**Divieto di avanzamento automatico:** registrare il requisito non autorizza build, nuovi modelli installati, permessi, branch merge, release o modifiche alla Beta.
