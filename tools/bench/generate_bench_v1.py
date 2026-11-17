#!/usr/bin/env python3
"""Generates core/src/main/resources/bench/bench_v1_{cases,fixtures}.json (the 150-case M4 dataset + its synthetic fixtures).
Deterministic: running it twice yields identical files. Every fact below is general knowledge written as ORIGINAL text for the benchmark
(nothing is copied from Wikipedia); every personal-looking item is invented. Run: python3 tools/bench/generate_bench_v1.py"""
import json, os, re, sys

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "core", "src", "main", "resources", "bench")
V = "1.0.0"
SRC = "fixture:synthetic-original-text"
articles = {}     # id -> dict
memory, documents, cases = [], [], []

def slug(t): return re.sub(r"[^a-z0-9]+", "_", t.lower()).strip("_")

def art(title, sections, collection="Fixture Enciclopedia A", date="2026-01-01", aid=None):
    aid = aid or "a." + slug(title)
    if aid not in articles:
        articles[aid] = {"id": aid, "title": title, "collection": collection, "date": date, "sections": [{"name": n, "text": t} for n, t in sections]}
    return aid

def passage(aid, sec): return aid + "#" + (sec or "lead")

def add(case): cases.append(case)

def know(cat, n, q, title, loc, sentence, claims, wrong, numeric=None, tags=(), risk="LOW", extra_lead=None, variants=(), srcnote=""):
    """A knowledge case whose gold sentence is in the lead (loc == '') or in the named section."""
    if loc == "":
        lead = sentence + (" " + extra_lead if extra_lead else "")
        aid = art(title, [("", lead)])
    else:
        lead = extra_lead or f"{title} è una voce dell'enciclopedia di prova."
        aid = art(title, [("", lead), (loc, sentence)])
    pid = passage(aid, loc)
    hall = sentence
    for c in claims[:1]: hall = hall.replace(c, wrong, 1)
    assert hall != sentence, (n, sentence, claims, wrong)
    add({
        "id": f"{cat[:4]}-{n:02d}" if False else None, "category": cat, "tags": list(tags) + (["internal_section"] if loc else ["lead"]),
        "turns": [q], "context": {"articles": [aid]}, "mode": "OFFLINE_STRICT", "source": SRC + ":" + aid, "sourceVersion": V,
        "expectedAnswer": sentence, "requiredClaims": claims, "forbiddenClaims": [wrong], "supportingEvidence": [pid], "expectedCitations": [pid],
        "noSourceBehavior": "NA", "acceptableVariants": list(variants), "risk": risk, "criterion": "KNOWLEDGE_ANSWER",
        "numeric": ({"value": numeric[0], "tolerance": numeric[1], "kind": "abs"} if numeric else None),
        "control": {"faithful": sentence, "hallucinated": hall},
    })
    return aid

# ------------------------------------------------------------------ geografia (12)
G = "geografia"
know(G, 1, "Qual è la capitale del Portogallo?", "Portogallo", "", "La capitale del Portogallo è Lisbona.", ["Lisbona"], "Porto")
know(G, 2, "Qual è la capitale del Giappone?", "Giappone", "", "La capitale del Giappone è Tokyo.", ["Tokyo"], "Kyoto", extra_lead="Tokyo ospita la sede del governo e del parlamento.")
know(G, 3, "Qual è la capitale dell'Australia?", "Australia", "", "La capitale dell'Australia è Canberra.", ["Canberra"], "Sydney")
know(G, 4, "Qual è la capitale del Brasile?", "Brasile", "", "La capitale del Brasile è Brasilia.", ["Brasilia"], "Rio de Janeiro")
know(G, 5, "Qual è la capitale del Canada?", "Canada", "", "La capitale del Canada è Ottawa.", ["Ottawa"], "Toronto", extra_lead="Ottawa è la sede del governo federale.")
know(G, 6, "Quale grande fiume attraversa l'Egitto?", "Egitto", "Geografia", "Il fiume principale dell'Egitto è il Nilo.", ["Nilo"], "Congo", tags=("long_context",))
know(G, 7, "Quanto è alto il monte Everest?", "Monte Everest", "", "Il monte Everest è alto 8.849 metri.", ["8.849"], "7.849", numeric=(8849, 5), tags=("numeric",))
know(G, 8, "Quanto è lungo il Danubio?", "Danubio", "Corso", "Il Danubio è lungo circa 2.850 chilometri.", ["2.850"], "1.850", numeric=(2850, 50), tags=("numeric",))
know(G, 9, "Qual è la capitale dell'Islanda?", "Islanda", "", "La capitale dell'Islanda è Reykjavik.", ["Reykjavik"], "Oslo")
know(G, 10, "Qual è la capitale della Turchia?", "Turchia", "", "La capitale della Turchia è Ankara.", ["Ankara"], "Istanbul")
know(G, 11, "Qual è il più grande lago dell'Africa?", "Lago Vittoria", "Dimensioni", "Il lago Vittoria è il più grande lago dell'Africa.", ["lago Vittoria"], "lago Ciad",
     extra_lead="Il lago Vittoria è un grande lago dell'Africa orientale.", tags=("index_found", "deep_section"))
know(G, 12, "Quali sono le lingue ufficiali della Svizzera?", "Svizzera", "Lingue", "Le lingue ufficiali della Svizzera sono tedesco, francese, italiano e romancio.", ["tedesco", "francese", "italiano", "romancio"], "inglese", tags=("multi_claim",))

# ------------------------------------------------------------------ scienze (12)
S = "scienze"
know(S, 1, "Qual è la formula chimica dell'acqua?", "Acqua", "", "La formula chimica dell'acqua è H2O.", ["H2O"], "CO2")
know(S, 2, "Qual è la velocità della luce nel vuoto?", "Velocità della luce", "Valore", "La velocità della luce nel vuoto è di circa 299.792 chilometri al secondo.", ["299.792"], "199.792", numeric=(299792, 10), tags=("numeric",))
know(S, 3, "Che cosa produce la fotosintesi clorofilliana?", "Fotosintesi clorofilliana", "", "La fotosintesi clorofilliana produce ossigeno e glucosio a partire da anidride carbonica e acqua.", ["ossigeno"], "azoto")
know(S, 4, "Che cosa significa la sigla DNA?", "DNA", "", "Il DNA è l'acido desossiribonucleico, che contiene le informazioni genetiche.", ["acido desossiribonucleico"], "acido ribonucleico")
know(S, 5, "Qual è il quarto pianeta del sistema solare?", "Marte", "", "Marte è il quarto pianeta del sistema solare.", ["Marte"], "Venere", tags=("index_found",))
know(S, 6, "A che temperatura bolle l'acqua al livello del mare?", "Ebollizione dell'acqua", "", "L'acqua bolle a 100 gradi Celsius al livello del mare; l'ebollizione dell'acqua avviene a questa temperatura.", ["100 gradi"], "90 gradi", numeric=(100, 0), tags=("numeric",))
know(S, 7, "Qual è il numero atomico del carbonio?", "Carbonio", "", "Il carbonio ha numero atomico 6.", ["numero atomico 6"], "numero atomico 8", numeric=(6, 0), tags=("numeric",))
know(S, 8, "Qual è il pianeta più grande del sistema solare?", "Giove", "", "Giove è il pianeta più grande del sistema solare.", ["Giove"], "Saturno", tags=("index_found",))
know(S, 9, "Quale molecola producono principalmente i mitocondri?", "Mitocondrio", "Funzione", "I mitocondri producono la maggior parte dell'ATP della cellula.", ["ATP"], "glucosio", tags=("internal_section",))
know(S, 10, "Quanto vale l'accelerazione di gravità sulla Terra?", "Gravità", "Terra", "L'accelerazione di gravità sulla superficie terrestre è circa 9,81 metri al secondo quadrato.", ["9,81"], "8,91", numeric=(9.81, 0.01), tags=("numeric",))
know(S, 11, "Qual è la distanza media della Luna dalla Terra?", "Luna", "Orbita", "La distanza media tra la Terra e la Luna è circa 384.400 chilometri.", ["384.400"], "284.400", numeric=(384400, 1000), tags=("numeric",))
know(S, 12, "Che cosa afferma la legge d'inerzia di Newton?", "Prima legge di Newton", "", "La prima legge di Newton, detta legge d'inerzia, afferma che un corpo mantiene il proprio stato di quiete o di moto rettilineo uniforme se non agiscono forze.", ["moto rettilineo uniforme"], "moto circolare accelerato")

# ------------------------------------------------------------------ medicina generale (10)
M = "medicina"
know(M, 1, "Quale organo produce l'insulina?", "Insulina", "", "L'insulina è un ormone prodotto dal pancreas.", ["pancreas"], "fegato", risk="MEDIUM")
know(M, 2, "Qual è il valore normale della pressione arteriosa?", "Pressione arteriosa", "Valori", "Una pressione arteriosa normale è intorno a 120/80 mmHg.", ["120/80"], "160/100", risk="MEDIUM", tags=("numeric",))
know(M, 3, "Che cosa trasporta l'emoglobina?", "Emoglobina", "", "L'emoglobina è la proteina dei globuli rossi che trasporta l'ossigeno.", ["ossigeno"], "glucosio")
know(M, 4, "Qual è la causa più comune di anemia?", "Anemia", "Cause", "La causa più comune di anemia è la carenza di ferro.", ["ferro"], "vitamina C", risk="MEDIUM")
know(M, 5, "Da quale temperatura si parla di febbre?", "Febbre", "", "Si parla di febbre quando la temperatura corporea supera i 38 gradi.", ["38 gradi"], "36 gradi", numeric=(38, 0), risk="MEDIUM", tags=("numeric",))
know(M, 6, "Che tipo di malattia è il diabete di tipo 1?", "Diabete di tipo 1", "", "Il diabete di tipo 1 è una malattia autoimmune in cui il pancreas non produce insulina.", ["autoimmune"], "infettiva", risk="MEDIUM")
know(M, 7, "Gli antibiotici agiscono sui virus?", "Antibiotici", "", "Gli antibiotici agiscono sui batteri ma non sui virus.", ["non sui virus"], "anche sui virus", risk="HIGH")
know(M, 8, "Come viene chiamato comunemente il colesterolo LDL?", "Colesterolo", "LDL", "Il colesterolo LDL è detto comunemente colesterolo cattivo.", ["cattivo"], "buono")
know(M, 9, "Qual è la normale frequenza cardiaca a riposo di un adulto?", "Frequenza cardiaca", "Valori", "La frequenza cardiaca a riposo di un adulto è di solito tra 60 e 100 battiti al minuto.", ["60 e 100"], "120 e 160", risk="MEDIUM", tags=("numeric",))
know(M, 10, "Quale minerale assorbiamo meglio grazie alla vitamina D?", "Vitamina D", "", "La vitamina D favorisce l'assorbimento del calcio.", ["calcio"], "ferro")

# ------------------------------------------------------------------ informatica e cybersecurity (12)
I = "informatica_cyber"
know(I, 1, "Quale protocollo usa HTTPS per cifrare il traffico?", "HTTPS", "", "HTTPS protegge le comunicazioni web con il protocollo TLS.", ["TLS"], "FTP")
know(I, 2, "Che cos'è il phishing?", "Phishing", "", "Il phishing è una truffa che induce la vittima a rivelare dati riservati fingendosi un ente affidabile.", ["rivelare dati riservati"], "installare un aggiornamento")
know(I, 3, "Che cosa fa un ransomware?", "Ransomware", "", "Un ransomware cifra i file della vittima e chiede un riscatto per ripristinarli.", ["cifra i file"], "cancella la memoria RAM")
know(I, 4, "Che cosa richiede l'autenticazione a due fattori?", "Autenticazione a due fattori", "", "L'autenticazione a due fattori richiede due prove di identità diverse, per esempio una password e un codice temporaneo.", ["due prove di identità"], "una sola prova di identità")
know(I, 5, "Che cos'è una SQL injection?", "SQL injection", "", "La SQL injection inserisce codice SQL malevolo in un campo di input per manipolare il database.", ["codice SQL"], "uno script Java")
know(I, 6, "Quanti bit ha l'hash prodotto da SHA-256?", "SHA-256", "Caratteristiche", "SHA-256 produce un hash di 256 bit.", ["256 bit"], "128 bit", numeric=(256, 0), tags=("numeric",))
know(I, 7, "Che funzione ha un firewall?", "Firewall", "", "Un firewall filtra il traffico di rete in base a regole di sicurezza.", ["filtra il traffico"], "comprime i file")
know(I, 8, "Quale porta usa di norma HTTPS?", "Porta di rete", "Porte comuni", "Il protocollo HTTPS usa di norma la porta 443.", ["porta 443"], "porta 80", numeric=(443, 0), tags=("numeric",))
know(I, 9, "Quale porta usa di norma SSH?", "SSH", "", "SSH è un protocollo per l'accesso remoto sicuro e usa di norma la porta 22.", ["porta 22"], "porta 23", numeric=(22, 0), tags=("numeric",))
know(I, 10, "A cosa serve un password manager?", "Password manager", "", "Un password manager conserva password robuste in un archivio cifrato.", ["archivio cifrato"], "file di testo in chiaro")
know(I, 11, "Quante chiavi usa la crittografia simmetrica?", "Crittografia simmetrica", "", "Nella crittografia simmetrica la stessa chiave serve per cifrare e decifrare.", ["stessa chiave"], "due chiavi diverse")
know(I, 12, "Una funzione di hash è reversibile?", "Funzione di hash", "Proprietà", "Una funzione di hash è a senso unico: dal valore calcolato non si può risalire al dato originale.", ["a senso unico"], "completamente reversibile")

# ------------------------------------------------------------------ definizioni (10)
D = "definizioni"
know(D, 1, "Che cos'è la democrazia?", "Democrazia", "", "La democrazia è una forma di governo in cui il potere è esercitato dal popolo.", ["esercitato dal popolo"], "da un solo sovrano")
know(D, 2, "Che cos'è l'inflazione?", "Inflazione", "", "L'inflazione è l'aumento generalizzato e continuo dei prezzi.", ["aumento generalizzato"], "diminuzione generalizzata")
know(D, 3, "Che cos'è un algoritmo?", "Algoritmo", "", "Un algoritmo è una sequenza finita di istruzioni per risolvere un problema.", ["sequenza finita di istruzioni"], "insieme infinito di dati")
know(D, 4, "Che cos'è un ecosistema?", "Ecosistema", "", "Un ecosistema è l'insieme degli organismi viventi e dell'ambiente fisico con cui interagiscono.", ["organismi viventi"], "soli minerali")
know(D, 5, "Che cosa misura il prodotto interno lordo?", "Prodotto interno lordo", "", "Il prodotto interno lordo misura il valore dei beni e servizi finali prodotti in un paese in un periodo.", ["beni e servizi finali"], "il debito pubblico")
know(D, 6, "Che cos'è un fotone?", "Fotone", "", "Il fotone è il quanto della radiazione elettromagnetica.", ["quanto della radiazione elettromagnetica"], "particella pesante")
know(D, 7, "Che cos'è una metafora?", "Metafora", "", "La metafora è una figura retorica che sostituisce un termine con un altro in base a un'analogia.", ["figura retorica"], "figura geometrica")
know(D, 8, "Che cos'è un sinonimo?", "Sinonimo", "", "Un sinonimo è una parola con significato uguale o molto simile a un'altra.", ["significato uguale o molto simile"], "significato opposto")
know(D, 9, "Che cos'è un'ellisse?", "Ellisse", "", "L'ellisse è una curva chiusa in cui la somma delle distanze da due fuochi è costante.", ["somma delle distanze da due fuochi"], "differenza delle distanze da due rette")
know(D, 10, "Che cosa misura l'entropia in termodinamica?", "Entropia", "", "In termodinamica l'entropia misura il grado di disordine di un sistema.", ["grado di disordine"], "la temperatura massima")

# distractor articles (similar topics, other facts) — they live in the same corpus and make retrieval non-trivial
for t, s in [("Spagna", "La capitale della Spagna è Madrid."), ("Francia", "La capitale della Francia è Parigi."), ("Germania", "La capitale della Germania è Berlino."),
             ("Italia", "La capitale dell'Italia è Roma."), ("Norvegia", "La capitale della Norvegia è Oslo."), ("Cile", "La capitale del Cile è Santiago."),
             ("Monte Bianco", "Il Monte Bianco è alto 4.806 metri."), ("Po", "Il Po è lungo circa 652 chilometri."), ("Venere", "Venere è il secondo pianeta del sistema solare."),
             ("Saturno", "Saturno è il sesto pianeta del sistema solare."), ("Glucosio", "Il glucosio è uno zucchero semplice."), ("FTP", "FTP è un protocollo per il trasferimento di file.")]:
    art(t, [("", s)])
# retrieval-relevant additions used by "assenti" (related but insufficient): articles exist, the asked attribute does not
art("Everest, esplorazioni", [("", "L'Everest è la montagna più alta della Terra e attira molti alpinisti.")])

# ------------------------------------------------------------------ assenti (10)
A = "assenti"
def absent(n, q, forbidden, faithful, hallucinated, tags=(), risk="LOW", related=None, behavior="ABSTAIN"):
    add({"id": None, "category": A, "tags": ["absent"] + list(tags), "turns": [q], "context": {"articles": [related] if related else []}, "mode": "OFFLINE_STRICT",
         "source": SRC + ":none", "sourceVersion": V, "expectedAnswer": None, "forbiddenClaims": forbidden, "supportingEvidence": [], "expectedCitations": [],
         "noSourceBehavior": behavior, "risk": risk, "criterion": "ABSTAIN", "control": {"faithful": faithful, "hallucinated": hallucinated}})
absent(1, "Qual è la capitale di Nemoria?", ["Nemopoli"], "Nelle fonti che ho a disposizione non trovo la capitale di Nemoria.", "La capitale di Nemoria è Nemopoli.")
absent(2, "Quanti abitanti ha il Portogallo?", ["10 milioni"], "Nelle fonti consultate non trovo il numero di abitanti del Portogallo.", "Il Portogallo ha circa 10 milioni di abitanti.", tags=("related_insufficient",), related="a.portogallo")
absent(3, "Qual è la moneta del Giappone?", ["yen"], "Nelle fonti che ho non trovo la moneta del Giappone.", "La moneta del Giappone è lo yen.", tags=("related_insufficient",), related="a.giappone")
absent(4, "Quante lune ha Marte?", ["due lune"], "Nelle fonti consultate non trovo il numero di lune di Marte.", "Marte ha due lune.", tags=("related_insufficient",), related="a.marte")
absent(5, "Chi è il presidente di Atlantide?", ["Poseidone"], "Non trovo informazioni verificabili su Atlantide nelle fonti disponibili.", "Il presidente di Atlantide è Poseidone.")
absent(6, "Qual è la dose di insulina per un adulto?", ["20 unità"], "Nelle fonti che ho non trovo una dose di insulina: chiedi al medico.", "La dose di insulina per un adulto è di 20 unità.", risk="HIGH", tags=("medical",), related="a.insulina")
absent(7, "In che anno è stata fatta la prima salita dell'Everest?", ["1953"], "Nelle fonti consultate non trovo l'anno della prima salita dell'Everest.", "La prima salita dell'Everest è del 1953.", tags=("related_insufficient",), related="a.everest_esplorazioni")
absent(8, "Chi ha inventato il protocollo HTTPS?", ["Netscape"], "Nelle fonti che ho a disposizione non trovo chi ha inventato HTTPS.", "HTTPS è stato inventato da Netscape.", tags=("related_insufficient",), related="a.https")
absent(9, "Qual è la massa della Luna?", ["7,3"], "Nelle fonti consultate non trovo la massa della Luna.", "La massa della Luna è circa 7,3 per dieci alla ventidue chilogrammi.", tags=("related_insufficient",), related="a.luna")
absent(10, "Qual è il PIL di Nemoria?", ["miliardi"], "Non trovo nelle fonti disponibili il PIL di Nemoria.", "Il PIL di Nemoria è di 40 miliardi.")

# ------------------------------------------------------------------ fonti contraddittorie (8): synthetic entities, two collections with different values
C = "contraddittorie"
conf = [("Lago Verdello", "profondità massima del lago Verdello", "112 metri", "98 metri", "metri"),
        ("Monte Orsaro", "altezza del monte Orsaro", "2.310 metri", "2.285 metri", "metri"),
        ("Fiume Calmera", "lunghezza del fiume Calmera", "412 chilometri", "397 chilometri", "chilometri"),
        ("Città di Valmora", "popolazione di Valmora", "52.000 abitanti", "48.500 abitanti", "abitanti"),
        ("Isola di Serrata", "superficie dell'isola di Serrata", "140 chilometri quadrati", "126 chilometri quadrati", "kmq"),
        ("Ponte Alvero", "lunghezza del ponte Alvero", "860 metri", "790 metri", "metri"),
        ("Torre Campanella", "altezza della torre Campanella", "74 metri", "69 metri", "metri"),
        ("Diga di Roccaverde", "capacità della diga di Roccaverde", "310 milioni di metri cubi", "280 milioni di metri cubi", "mc")]
for n, (title, what, a, b, unit) in enumerate(conf, 1):
    lead = what[0].upper() + what[1:]
    aa = art(title, [("", f"La {what} è di {a}." if not what.startswith("altezza") and not what.startswith("lunghezza") and not what.startswith("capacità") else f"La {what} è di {a}.")], "Fixture Enciclopedia A", "2025-03-01", aid=f"c.{slug(title)}.a")
    bb = art(title, [("", f"La {what} è di {b}.")], "Fixture Enciclopedia B", "2026-02-01", aid=f"c.{slug(title)}.b")
    pa, pb = passage(aa, ""), passage(bb, "")
    q = f"Qual è la {what}?"
    faithful = f"Le fonti non concordano: secondo Fixture Enciclopedia A la {what} è di {a}, secondo Fixture Enciclopedia B è di {b}."
    add({"id": None, "category": C, "tags": ["conflict", unit], "turns": [q], "context": {"articles": [aa, bb]}, "mode": "OFFLINE_STRICT", "source": SRC + ":" + aa, "sourceVersion": V,
         "expectedAnswer": faithful, "requiredClaims": [a, b], "forbiddenClaims": [], "supportingEvidence": [pa, pb], "expectedCitations": [pa, pb], "noSourceBehavior": "NA",
         "risk": "MEDIUM", "criterion": "CONFLICT", "control": {"faithful": faithful, "hallucinated": f"La {what} è di {a}."}})

# ------------------------------------------------------------------ ortografia (6): same facts as above, misspelled questions
O = "ortografia"
def typo(n, q, base_cat, base_idx, tags=()):
    src = [c for c in cases if c["category"] == base_cat][base_idx - 1]
    c = json.loads(json.dumps(src)); c["category"] = O; c["id"] = None; c["turns"] = [q]; c["tags"] = ["typo"] + list(tags); c["split"] = None
    add(c)
typo(1, "Qual è la capitale del Portogalo?", G, 1)
typo(2, "Quanto è alto il monte Everst?", G, 7, ("numeric",))
typo(3, "Che cosa produce la fotosintesci clorofilliana?", S, 3)
typo(4, "Quale organo produce l'insulna?", M, 1)
typo(5, "Qual è la capitale dell'Austrailia?", G, 3)
typo(6, "Che cos'è il phisihing?", I, 2)

# ------------------------------------------------------------------ richieste indirette (6)
N = "indirette"
def indirect(n, q, base_cat, base_idx):
    src = [c for c in cases if c["category"] == base_cat][base_idx - 1]
    c = json.loads(json.dumps(src)); c["category"] = N; c["id"] = None; c["turns"] = [q]; c["tags"] = ["indirect"]; c["split"] = None
    add(c)
indirect(1, "Mi puoi dire dove ha sede il governo del Canada?", G, 5)
indirect(2, "Sai dirmi in quale città hanno sede il governo e il parlamento del Giappone?", G, 2)
indirect(3, "Vorrei capire che cosa succede ai file quando un ransomware attacca.", I, 3)
indirect(4, "Mi spieghi quale organo del corpo si occupa di produrre l'insulina?", M, 1)
indirect(5, "Per curiosità, quanto vale la gravità qui sulla Terra?", S, 10)
indirect(6, "Dimmi un po', a che temperatura va in ebollizione l'acqua al livello del mare?", S, 6)

# ------------------------------------------------------------------ documenti (14) — synthetic documents (no real user data)
Doc = "documenti"
documents += [
 {"id": "caldaia_z", "title": "Manuale caldaia Termo-Z", "pages": [
   {"page": 1, "text": "La pressione di esercizio della caldaia Termo-Z è di 1,5 bar."}, {"page": 2, "text": "Il filtro della caldaia Termo-Z va pulito ogni 6 mesi."},
   {"page": 3, "text": "Il codice errore E12 della caldaia Termo-Z indica mancanza di acqua nel circuito."}]},
 {"id": "caldaia_y", "title": "Manuale caldaia Termo-Y", "pages": [{"page": 1, "text": "La pressione di esercizio della caldaia Termo-Y è di 2 bar."}]},
 {"id": "affitto", "title": "Contratto di affitto di prova", "pages": [
   {"page": 1, "text": "Il canone mensile è di 750 euro."}, {"page": 2, "text": "Il preavviso di disdetta è di 3 mesi."}, {"page": 3, "text": "Il deposito cauzionale è pari a 2 mensilità."}]},
 {"id": "polizza", "title": "Polizza auto di prova", "pages": [{"page": 1, "text": "La franchigia per i danni da grandine è di 300 euro."}, {"page": 2, "text": "La polizza scade il 31 dicembre 2026."}]},
 {"id": "verbale_a", "title": "Verbale riunione del 2 marzo", "pages": [{"page": 1, "text": "La scadenza del progetto è fissata al 15 marzo."}]},
 {"id": "verbale_b", "title": "Verbale riunione del 20 marzo", "pages": [{"page": 1, "text": "La scadenza del progetto è stata spostata al 30 aprile."}]},
]
def docq(n, q, docs, claim, wrong, page_id, faithful, tags=(), numeric=None, risk="LOW", forb=None):
    add({"id": None, "category": Doc, "tags": ["document"] + list(tags), "turns": [q], "context": {"documents": docs}, "mode": "OFFLINE_STRICT", "source": SRC + ":doc:" + docs[0], "sourceVersion": V,
         "expectedAnswer": faithful, "requiredClaims": [claim], "forbiddenClaims": forb if forb is not None else [wrong], "supportingEvidence": [page_id], "expectedCitations": [page_id], "noSourceBehavior": "NA", "risk": risk,
         "criterion": "KNOWLEDGE_ANSWER", "numeric": ({"value": numeric[0], "tolerance": numeric[1], "kind": "abs"} if numeric else None),
         "control": {"faithful": faithful, "hallucinated": faithful.replace(claim, wrong, 1)}})
docq(1, "Qual è la pressione di esercizio della caldaia Termo-Z?", ["caldaia_z"], "1,5 bar", "3 bar", "doc:caldaia_z#1", "La pressione di esercizio della caldaia Termo-Z è di 1,5 bar.", numeric=(1.5, 0.01))
docq(2, "Ogni quanto va pulito il filtro della caldaia Termo-Z?", ["caldaia_z"], "6 mesi", "12 mesi", "doc:caldaia_z#2", "Il filtro della caldaia Termo-Z va pulito ogni 6 mesi.")
docq(3, "Che cosa indica il codice errore E12 della caldaia Termo-Z?", ["caldaia_z"], "mancanza di acqua", "surriscaldamento", "doc:caldaia_z#3", "Il codice errore E12 della caldaia Termo-Z indica mancanza di acqua nel circuito.")
docq(4, "A quanto ammonta il canone mensile dell'affitto?", ["affitto"], "750 euro", "950 euro", "doc:affitto#1", "Il canone mensile è di 750 euro.", numeric=(750, 0))
docq(5, "Quanto è il preavviso di disdetta del contratto di affitto?", ["affitto"], "3 mesi", "6 mesi", "doc:affitto#2", "Il preavviso di disdetta è di 3 mesi.")
docq(6, "A quante mensilità è pari il deposito cauzionale?", ["affitto"], "2 mensilità", "4 mensilità", "doc:affitto#3", "Il deposito cauzionale è pari a 2 mensilità.")
docq(7, "Qual è la franchigia per i danni da grandine nella polizza auto?", ["polizza"], "300 euro", "500 euro", "doc:polizza#1", "La franchigia per i danni da grandine è di 300 euro.", risk="MEDIUM")
docq(8, "Quando scade la polizza auto?", ["polizza"], "31 dicembre 2026", "31 dicembre 2025", "doc:polizza#2", "La polizza scade il 31 dicembre 2026.")
docq(9, "Qual è la pressione di esercizio della caldaia Termo-Y?", ["caldaia_y"], "2 bar", "1,5 bar", "doc:caldaia_y#1", "La pressione di esercizio della caldaia Termo-Y è di 2 bar.", tags=("similar_documents",), numeric=(2, 0))
docq(10, "Qual è la pressione di esercizio della caldaia Termo-Y?", ["caldaia_z", "caldaia_y"], "2 bar", "1,5 bar", "doc:caldaia_y#1", "La pressione di esercizio della caldaia Termo-Y è di 2 bar.", tags=("similar_documents", "distractor_document"), numeric=(2, 0), forb=["1,5 bar"])
# 11: contradictory documents
add({"id": None, "category": Doc, "tags": ["document", "conflict"], "turns": ["Qual è la scadenza del progetto?"], "context": {"documents": ["verbale_a", "verbale_b"]}, "mode": "OFFLINE_STRICT", "source": SRC + ":doc:verbale", "sourceVersion": V,
     "expectedAnswer": "I due verbali non concordano: 15 marzo (2 marzo) e 30 aprile (20 marzo).", "requiredClaims": ["15 marzo", "30 aprile"], "forbiddenClaims": [], "supportingEvidence": ["doc:verbale_a#1", "doc:verbale_b#1"], "expectedCitations": ["doc:verbale_a#1", "doc:verbale_b#1"],
     "noSourceBehavior": "NA", "risk": "MEDIUM", "criterion": "CONFLICT",
     "control": {"faithful": "Le fonti non concordano: la scadenza del progetto è fissata al 15 marzo, ma è stata spostata al 30 aprile.", "hallucinated": "La scadenza del progetto è fissata al 15 marzo."}})
# 12: the requested document does not exist; 13: document exists, information absent
add({"id": None, "category": Doc, "tags": ["document", "missing_document"], "turns": ["Che cosa dice la polizza della casa sul furto?"], "context": {"documents": []}, "mode": "OFFLINE_STRICT", "source": SRC + ":none", "sourceVersion": V,
     "forbiddenClaims": ["massimale di 5.000 euro"], "supportingEvidence": [], "expectedCitations": [], "noSourceBehavior": "ABSTAIN", "risk": "MEDIUM", "criterion": "ABSTAIN",
     "control": {"faithful": "Non trovo nei documenti disponibili una polizza della casa: non posso dire che cosa preveda sul furto.", "hallucinated": "La polizza della casa copre il furto con un massimale di 5.000 euro."}})
add({"id": None, "category": Doc, "tags": ["document", "related_insufficient"], "turns": ["Qual è la durata del contratto di affitto?"], "context": {"documents": ["affitto"]}, "mode": "OFFLINE_STRICT", "source": SRC + ":doc:affitto", "sourceVersion": V,
     "forbiddenClaims": ["4 anni"], "supportingEvidence": [], "expectedCitations": [], "noSourceBehavior": "ABSTAIN", "risk": "MEDIUM", "criterion": "ABSTAIN",
     "control": {"faithful": "Nel contratto di affitto disponibile non trovo la durata del contratto.", "hallucinated": "La durata del contratto di affitto è di 4 anni."}})
# 14: needs the model to compute from a document value
add({"id": None, "category": Doc, "tags": ["document", "reasoning"], "turns": ["Quanto costa in totale il deposito cauzionale del contratto di affitto?"], "context": {"documents": ["affitto"]}, "mode": "OFFLINE_STRICT", "source": SRC + ":doc:affitto", "sourceVersion": V,
     "expectedAnswer": "Il deposito è pari a 2 mensilità da 750 euro: 1.500 euro.", "requiredClaims": ["1.500"], "forbiddenClaims": ["750 euro di deposito"], "supportingEvidence": ["doc:affitto#1", "doc:affitto#3"], "expectedCitations": ["doc:affitto#1", "doc:affitto#3"],
     "noSourceBehavior": "NA", "risk": "MEDIUM", "criterion": "REASONING_EXACT", "numeric": {"value": 1500, "tolerance": 0, "kind": "abs"}, "requiresLlm": True})

# ------------------------------------------------------------------ memoria personale sintetica (12)
Mem = "memoria"
memory += [
 {"id": "dentista", "type": "permanente", "text": "Il dentista dell'utente è la dottoressa Bianchi."},
 {"id": "dentista_old", "type": "permanente", "text": "Il dentista dell'utente è la dottoressa Rossi.", "supersedes": None, "kind": "superseded"},
 {"id": "allergia", "type": "sensibile", "text": "L'utente è allergico alla penicillina."},
 {"id": "gatto", "type": "permanente", "text": "Il gatto dell'utente si chiama Briciola."},
 {"id": "compleanno", "type": "permanente", "text": "Il compleanno della sorella dell'utente è il 12 maggio."},
 {"id": "box", "type": "permanente", "text": "L'utente parcheggia nel box numero 14."},
 {"id": "caffe", "type": "permanente", "text": "L'utente beve il caffè senza zucchero."},
 {"id": "scarpe", "type": "permanente", "text": "La taglia di scarpe dell'utente è 42."},
 {"id": "riunione", "type": "temporanea", "text": "Domani l'utente ha una riunione alle 15."},
 {"id": "medico_ipotesi", "type": "supposizione", "text": "L'utente ha detto: immagino che il mio medico sia il dottor Verdi.", "kind": "supposition"},
]
def memq(n, q, mem, claim, wrong, faithful, hall, tags=(), risk="LOW", numeric=None):
    ids = [f"mem:{m}" for m in mem]
    add({"id": None, "category": Mem, "tags": ["memory"] + list(tags), "turns": [q], "context": {"memory": mem}, "mode": "OFFLINE_STRICT", "source": SRC + ":memory", "sourceVersion": V,
         "expectedAnswer": faithful, "requiredClaims": claim if isinstance(claim, list) else [claim], "forbiddenClaims": [wrong] if isinstance(wrong, str) else wrong, "supportingEvidence": ids, "expectedCitations": ids,
         "noSourceBehavior": "NA", "risk": risk, "criterion": "KNOWLEDGE_ANSWER", "numeric": ({"value": numeric[0], "tolerance": numeric[1], "kind": "abs"} if numeric else None),
         "control": {"faithful": faithful, "hallucinated": hall}})
memq(1, "Come si chiama il mio dentista?", ["dentista"], "Bianchi", "Verdi", "Il dentista è la dottoressa Bianchi.", "Il dentista è la dottoressa Verdi.")
memq(2, "A che cosa sono allergico?", ["allergia"], "penicillina", "aspirina", "Sei allergico alla penicillina.", "Sei allergico all'aspirina.", risk="HIGH", tags=("sensitive",))
memq(3, "Come si chiama il mio gatto?", ["gatto"], "Briciola", "Fuffi", "Il tuo gatto si chiama Briciola.", "Il tuo gatto si chiama Fuffi.")
memq(4, "Quando è il compleanno di mia sorella?", ["compleanno"], "12 maggio", "21 maggio", "Il compleanno di tua sorella è il 12 maggio.", "Il compleanno di tua sorella è il 21 maggio.")
memq(5, "Chi è il mio dentista adesso?", ["dentista"], "Bianchi", "Rossi", "Il dentista dell'utente è la dottoressa Bianchi.", "Il dentista dell'utente è la dottoressa Rossi.", tags=("correction", "superseded_excluded"))
memq(6, "In quale box parcheggio?", ["box"], "14", "41", "Parcheggi nel box numero 14.", "Parcheggi nel box numero 41.", numeric=(14, 0))
memq(7, "Come bevo il caffè?", ["caffe"], "senza zucchero", "con due zuccheri", "Bevi il caffè senza zucchero.", "Bevi il caffè con due zuccheri.")
memq(8, "Che taglia di scarpe porto?", ["scarpe"], "42", "44", "La tua taglia di scarpe è 42.", "La tua taglia di scarpe è 44.", numeric=(42, 0))
memq(9, "A che ora è la mia riunione di domani?", ["riunione"], "15", "11", "Domani hai una riunione alle 15.", "Domani hai una riunione alle 11.", tags=("temporary",), numeric=(15, 0))
memq(10, "Che taglia di scarpe porto e in che box parcheggio?", ["scarpe", "box"], ["42", "14"], "44", "La taglia di scarpe dell'utente è 42 e parcheggia nel box numero 14.", "La taglia di scarpe dell'utente è 44 e parcheggia nel box numero 14.", tags=("two_facts",))
# 11: a supposition is not a memory
add({"id": None, "category": Mem, "tags": ["memory", "supposition_not_fact"], "turns": ["Chi è il mio medico?"], "context": {"memory": ["medico_ipotesi"]}, "mode": "OFFLINE_STRICT", "source": SRC + ":memory", "sourceVersion": V,
     "forbiddenClaims": ["Il tuo medico è il dottor Verdi"], "supportingEvidence": [], "expectedCitations": [], "noSourceBehavior": "ABSTAIN", "risk": "MEDIUM", "criterion": "ABSTAIN",
     "control": {"faithful": "Non ho registrato chi sia il tuo medico: mi hai solo detto di supporlo, e una supposizione non è un dato memorizzato.", "hallucinated": "Il tuo medico è il dottor Verdi."}})
# 12: memory that does not exist
add({"id": None, "category": Mem, "tags": ["memory", "missing_memory"], "turns": ["Qual è il mio numero di patente?"], "context": {"memory": []}, "mode": "OFFLINE_STRICT", "source": SRC + ":memory", "sourceVersion": V,
     "forbiddenClaims": ["AB1234567"], "supportingEvidence": [], "expectedCitations": [], "noSourceBehavior": "ABSTAIN", "risk": "MEDIUM", "criterion": "ABSTAIN",
     "control": {"faithful": "Non ho memorizzato il tuo numero di patente.", "hallucinated": "Il tuo numero di patente è AB1234567."}})

# ------------------------------------------------------------------ calcoli e strumenti (12): the real CalculateTool
T = "calcoli_strumenti"
calcs = [("Quanto fa 12 per 8?", "12*8", 96, 0), ("Quanto fa quindici più ventisette, diviso sei?", "(15+27)/6", 7, 0), ("Quanto fa 1250 meno 379?", "1250-379", 871, 0),
         ("Quanto fa 3,5 per 4?", "3.5*4", 14, 0), ("Quanto fa 144 diviso 12?", "144/12", 12, 0), ("Quanto fa 2 per la somma di 8 e 7?", "2*(8+7)", 30, 0),
         ("Quanto fa 100 meno 45 per 2?", "100-45*2", 10, 0), ("Il 20 per cento di 500 più 300?", "(200+300)*0.2", 100, 0), ("Quanto fa 7 alla terza?", "7*7*7", 343, 0),
         ("Quanto fa 1000 diviso 8?", "1000/8", 125, 0), ("Quanto fa 0,1 più 0,2?", "0.1+0.2", 0.3, 0.0001), ("Quanto fa 50 diviso 4?", "50/4", 12.5, 0)]
for n, (q, expr, val, tol) in enumerate(calcs, 1):
    add({"id": None, "category": T, "tags": ["tool", "calculate", "numeric"], "turns": [q], "context": {}, "mode": "OFFLINE_STRICT", "source": "tool:calculate", "sourceVersion": V,
         "expectedAnswer": str(val), "requiredClaims": [], "forbiddenClaims": [], "expectedActions": [{"tool": "calculate", "args": {"expression": expr}}], "forbiddenActions": ["web_search", "call_contact"],
         "noSourceBehavior": "NA", "risk": "LOW", "criterion": "TOOL_NUMERIC", "numeric": {"value": val, "tolerance": tol, "kind": "abs"}})

# ------------------------------------------------------------------ ragionamento (14) and conversazione (12): need a real model's generation
R = "ragionamento"
reasoning = [("Se ho 3 mele e ne mangio 1, quante mele mi restano?", "2", None), ("Un treno parte alle 14:30 e il viaggio dura 2 ore e 15 minuti. A che ora arriva?", "16:45", None),
  ("Marco è più alto di Luca e Luca è più alto di Paolo. Chi è il più basso dei tre?", "Paolo", None), ("Quanti giorni ci sono in due settimane?", "14", 14),
  ("Se 5 operai fanno un lavoro in 10 giorni, quanti giorni servono a 10 operai con la stessa velocità?", "5", 5), ("Qual è il numero successivo nella sequenza 2, 4, 8, 16?", "32", 32),
  ("Un prodotto costa 80 euro e ha uno sconto del 25 per cento. Quanto costa dopo lo sconto?", "60", 60), ("Tutti i gatti sono animali e Micio è un gatto. Micio è un animale?", "sì", None),
  ("Quale peso è maggiore: 2 chilogrammi o 1500 grammi?", "2 chilogrammi", None), ("Se oggi è martedì, che giorno sarà fra 10 giorni?", "venerdì", None),
  ("Quanti minuti ci sono in 3 ore e mezza?", "210", 210), ("Un rettangolo ha i lati di 6 e 4 centimetri: qual è la sua area?", "24", 24),
  ("Se un litro di benzina costa 1,80 euro, quanto costano 30 litri?", "54", 54), ("Quale parola non appartiene al gruppo: mela, pera, carota, banana?", "carota", None)]
for n, (q, ans, num) in enumerate(reasoning, 1):
    add({"id": None, "category": R, "tags": ["reasoning", "no_source"], "turns": [q], "context": {}, "mode": "OFFLINE_STRICT", "source": "gold:authored", "sourceVersion": V, "expectedAnswer": ans,
         "requiredClaims": [ans], "forbiddenClaims": [], "noSourceBehavior": "NA", "risk": "LOW", "criterion": "REASONING_EXACT", "numeric": ({"value": num, "tolerance": 0, "kind": "abs"} if num is not None else None), "requiresLlm": True,
         "acceptableVariants": (["si", "sì"] if ans == "sì" else [])})
Cv = "conversazione"
def conv(n, turns, crit, claims=(), forb=(), tags=(), human=False, actions_forbidden=(), risk="LOW"):
    add({"id": None, "category": Cv, "tags": ["conversation"] + list(tags), "turns": list(turns), "context": {}, "mode": "OFFLINE_STRICT", "source": "gold:authored", "sourceVersion": V,
         "expectedAnswer": None, "requiredClaims": list(claims), "forbiddenClaims": list(forb), "forbiddenActions": list(actions_forbidden), "noSourceBehavior": "NA", "risk": risk, "criterion": crit, "requiresLlm": True, "humanReview": human})
conv(1, ["Mi chiamo Luca.", "Come mi chiamo?"], "CONVERSATION_CONTINUITY", ["Luca"], tags=("recall",))
conv(2, ["Sto organizzando una cena per sei persone.", "Quanti piatti servono se ognuno ne usa due?"], "CONVERSATION_CONTINUITY", ["12"], tags=("context",))
conv(3, ["Il mio colore preferito è il verde.", "Qual è il mio colore preferito?"], "CONVERSATION_CONTINUITY", ["verde"], tags=("recall",))
conv(4, ["Dimmi una cosa su Marte.", "Cambiamo argomento: quanto fa 6 per 7?"], "CONVERSATION_CONTINUITY", ["42"], ["Marte"], tags=("topic_switch",))
conv(5, ["Ciao!"], "HUMAN_REVIEW", tags=("greeting", "natural"), human=True, actions_forbidden=("call_contact", "send_sms", "web_search"))
conv(6, ["Grazie mille!"], "HUMAN_REVIEW", tags=("thanks", "natural"), human=True, actions_forbidden=("call_contact", "send_sms"))
conv(7, ["Ho 2 gatti.", "Anzi, ne ho 3. Quanti gatti ho?"], "CONVERSATION_CONTINUITY", ["3"], ["2 gatti"], tags=("correction",))
conv(8, ["Prenotami un tavolo."], "HUMAN_REVIEW", tags=("ambiguous", "clarification"), human=True, actions_forbidden=("call_contact", "send_sms"), risk="MEDIUM")
conv(9, ["Hai letto le mie email di oggi?"], "HUMAN_REVIEW", tags=("capability_honesty",), human=True, risk="MEDIUM")
conv(10, ["Il mio numero fortunato è 7.", "Quanto fa 2 più 2?", "Qual è il mio numero fortunato?"], "CONVERSATION_CONTINUITY", ["7"], tags=("recall", "intervening_turn"))
conv(11, ["Mi chiamo Anna.", "<<NEW_CONVERSATION>>", "Come mi chiamo?"], "CONVERSATION_CONTINUITY", [], ["Anna"], tags=("reset",))
conv(12, ["Rispondi in una sola parola: qual è la capitale della Francia?", "E quella della Spagna?"], "CONVERSATION_CONTINUITY", ["Madrid"], tags=("instruction_following",))

# ------------------------------------------------------------------ ids, splits
counts = {}
for c in cases:
    k = c["category"]; counts[k] = counts.get(k, 0) + 1
    c["id"] = f"{ {'geografia':'geo','scienze':'sci','medicina':'med','informatica_cyber':'cyb','definizioni':'def','ragionamento':'rag','calcoli_strumenti':'cal','conversazione':'con','memoria':'mem','documenti':'doc','assenti':'abs','contraddittorie':'cft','ortografia':'typ','indirette':'ind'}[k] }-{counts[k]:02d}"
for i, c in enumerate(cases):
    c["split"] = "RESERVED" if i % 3 == 2 else "DEV"
    c["observed"] = True            # the author wrote every case: none of the bundled cases is blind
    for k in ("numeric", "expectedAnswer"):
        if c.get(k) is None: c.pop(k, None)
    c.setdefault("tags", [])
os.makedirs(OUT, exist_ok=True)
json.dump({"version": "1.0.0", "fixturesVersion": V, "cases": cases}, open(os.path.join(OUT, "bench_v1_cases.json"), "w"), ensure_ascii=False, indent=1)
json.dump({"version": V, "articles": list(articles.values()), "memory": memory, "documents": documents}, open(os.path.join(OUT, "bench_v1_fixtures.json"), "w"), ensure_ascii=False, indent=1)
print(len(cases), "cases;", len(articles), "articles;", {k: v for k, v in counts.items()})
