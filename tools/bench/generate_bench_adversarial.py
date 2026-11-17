#!/usr/bin/env python3
"""Generates core/src/main/resources/bench/bench_adv_{b,c}.json — ADVERSARIAL claim-safety sets (M4 closure).
B = regression set: the verifier rules were designed/tuned while looking at these cases.
C = verification set: written after the rules were designed and RUN ONCE; its failures are reported, the rules are not tuned to them.
NEITHER is blind (the author read both). Every source text is original, invented for the benchmark."""
import json, os, re
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "core", "src", "main", "resources", "bench")
def slug(t): return re.sub(r"[^a-z0-9]+", "_", t.lower()).strip("_")

def A(title, text, section=None, lead=None, coll="Fixture A", date="2026-01-01"):
    secs = [["", lead or text]] if section is None else [["", lead or f"{title} è una voce di prova."], [section, text]]
    return {"kind": "article", "id": "a." + slug(title) + ("." + slug(coll) if coll != "Fixture A" else ""), "title": title, "collection": coll, "date": date, "sections": secs}
def D(did, title, pages): return {"kind": "document", "id": did, "title": title, "pages": pages}

def case(set_, n, mutation, q, sources, cand, expect, markers=(), truth=None, cite=None, note=""):
    return {"id": f"adv{set_}-{n:02d}", "set": set_, "mutation": mutation, "question": q, "sources": sources, "candidate": cand, "expect": expect,
            "alteredMarkers": list(markers), "trueMarker": truth, "expectedCitation": cite, "note": note}

def build(set_, spec):
    out = []
    for i, args in enumerate(spec, 1): out.append(case(set_, i, *args))
    return out

# expect: NOT_VERIFIED (the altered claim must not be published as verified/allowed), VERIFIED (a benign restatement must be verified, citing the source),
#         ABSTAIN (an abstention must stay an abstention), CONFLICT (both values must be shown)
B = [
 ("subject", "Quale organo produce l'insulina?", [A("Insulina", "L'insulina è un ormone prodotto dal pancreas.")], "L'insulina è prodotta dal fegato.", "NOT_VERIFIED", ["fegato"]),
 ("subject", "Chi scoprì il polonio?", [A("Polonio", "Il polonio fu scoperto da Marie Curie nel 1898.")], "Il polonio fu scoperto da Albert Einstein nel 1898.", "NOT_VERIFIED", ["einstein"]),
 ("object", "Che cosa pompa il cuore?", [A("Cuore", "Il cuore pompa il sangue in tutto il corpo.")], "Il cuore pompa la linfa in tutto il corpo.", "NOT_VERIFIED", ["linfa"]),
 ("object", "Che cosa assorbono le radici?", [A("Radice", "Le radici assorbono acqua e sali minerali dal terreno.")], "Le radici assorbono luce e anidride carbonica dal terreno.", "NOT_VERIFIED", ["luce"]),
 ("property_swap", "Quanto è lungo il Po?", [A("Fiumi italiani", "Il Monte Bianco è alto 4.806 metri e il Po è lungo 652 chilometri.", section="Dati")], "Il Po è lungo 4.806 metri.", "NOT_VERIFIED", ["po è lungo 4.806"]),
 ("property_swap", "Qual è la larghezza della stanza?", [A("Stanza di prova", "La stanza misura 5 metri di lunghezza e 3 metri di larghezza.", section="Misure")], "La larghezza della stanza è di 5 metri.", "NOT_VERIFIED", ["larghezza della stanza è di 5"]),
 ("relation_inversion", "Di quale Stato Roma è la capitale?", [A("Roma", "Roma è la capitale dell'Italia.")], "L'Italia è la capitale di Roma.", "NOT_VERIFIED", ["italia è la capitale di roma"]),
 ("relation_inversion", "Chi insegue chi tra il gatto e il topo?", [A("Gatto e topo", "Il gatto insegue il topo.")], "Il topo insegue il gatto.", "NOT_VERIFIED", ["topo insegue il gatto"]),
 ("negation", "Il pomodoro è un frutto?", [A("Pomodoro", "Il pomodoro è un frutto.")], "Il pomodoro non è un frutto.", "NOT_VERIFIED", ["non è un frutto"]),
 ("negation", "L'acqua bolle a 100 gradi?", [A("Ebollizione", "L'acqua bolle a 100 gradi al livello del mare.")], "L'acqua non bolle a 100 gradi al livello del mare.", "NOT_VERIFIED", ["non bolle"]),
 ("name_substitution", "Chi ha scritto la Divina Commedia?", [A("Divina Commedia", "La Divina Commedia fu scritta da Dante Alighieri.")], "La Divina Commedia fu scritta da Petrarca.", "NOT_VERIFIED", ["petrarca"]),
 ("name_substitution", "Di chi è la scultura del David?", [A("David (scultura)", "Il David è una scultura di Michelangelo.")], "Il David è una scultura di Donatello.", "NOT_VERIFIED", ["donatello"]),
 ("quality_attribute", "Com'è il ghiaccio?", [A("Ghiaccio", "Il ghiaccio è freddo e solido.")], "Il ghiaccio è caldo e solido.", "NOT_VERIFIED", ["caldo"]),
 ("quality_attribute", "Di che alimentazione è il leone?", [A("Leone", "Il leone è un animale carnivoro.")], "Il leone è un animale erbivoro.", "NOT_VERIFIED", ["erbivoro"]),
 ("similar_documents", "Qual è la capacità del serbatoio del modello Alfa-A?", [D("alfa_a", "Scheda Alfa-A", ["Il serbatoio del modello Alfa-A ha una capacità di 40 litri."]), D("alfa_b", "Scheda Alfa-B", ["Il serbatoio del modello Alfa-B ha una capacità di 55 litri."])],
    "Il serbatoio del modello Alfa-A ha una capacità di 55 litri.", "NOT_VERIFIED", ["alfa-a ha una capacità di 55", "alfa-a&&55 litri"]),
 ("similar_documents", "Quanti volt richiede il caricatore C100?", [D("c100", "Caricatore C100", ["Il caricatore C100 richiede 12 volt."]), D("c200", "Caricatore C200", ["Il caricatore C200 richiede 24 volt."])],
    "Il caricatore C100 richiede 24 volt.", "NOT_VERIFIED", ["c100 richiede 24"]),
 ("partial_source", "Quanto è lungo il Tevere e dove passa?", [A("Tevere", "Il Tevere attraversa la città di Roma.")], "Il Tevere attraversa Roma ed è lungo 405 chilometri.", "NOT_VERIFIED", ["405"]),
 ("partial_source", "Chi ha costruito il ponte e quando?", [A("Ponte antico", "Il ponte antico fu costruito da un architetto romano.")], "Il ponte antico fu costruito da un architetto romano nel 1250.", "NOT_VERIFIED", ["1250"]),
 ("contradictory", "Quanto è profondo il lago Verdello?", [A("Lago Verdello", "La profondità massima del lago Verdello è di 112 metri.", coll="Fixture A", date="2025-03-01"), A("Lago Verdello", "La profondità massima del lago Verdello è di 98 metri.", coll="Fixture B", date="2026-02-01")],
    "La profondità massima del lago Verdello è di 112 metri.", "CONFLICT", ["112", "98"]),
 ("contradictory", "Quanti abitanti ha Valmora?", [A("Valmora", "Valmora ha 52.000 abitanti.", coll="Fixture A", date="2025-03-01"), A("Valmora", "Valmora ha 48.500 abitanti.", coll="Fixture B", date="2026-02-01")],
    "Valmora ha 48.500 abitanti.", "CONFLICT", ["48.500", "52.000"]),
 ("right_value_wrong_entity", "Quanto è alto il Cervino?", [A("Montagne alpine", "Il Monte Rosa è alto 4.634 metri. Il Cervino è alto 4.478 metri.", section="Altezze")], "Il Cervino è alto 4.634 metri.", "NOT_VERIFIED", ["cervino è alto 4.634"]),
 ("right_value_wrong_entity", "Quando nacque Galileo?", [A("Nascite", "Galileo nacque nel 1564. Newton nacque nel 1643.", section="Date")], "Galileo nacque nel 1643.", "NOT_VERIFIED", ["galileo nacque nel 1643"]),
 ("abstain_unsupported", "Chi ha progettato la torre di Valmora?", [A("Torre di Valmora", "La torre di Valmora è alta 60 metri.")], "La torre di Valmora è stata progettata da Brunelleschi.", "NOT_VERIFIED", ["brunelleschi"]),
 ("abstain_correct", "Chi ha inventato il protocollo SSH?", [A("Porta SSH", "Il protocollo SSH usa di norma la porta 22.")], "Nelle fonti che ho non trovo chi ha inventato SSH.", "ABSTAIN", ["porta 22"]),
 ("abstain_correct", "In che anno fu fondata Valmora?", [A("Valmora", "Valmora ha 52.000 abitanti.")], "Non trovo nelle fonti l'anno di fondazione di Valmora.", "ABSTAIN", ["52.000"]),
 ("irrelevant_extract", "Qual è la moneta della Nemoria?", [A("Nemoria, geografia", "La Nemoria confina con tre Stati e ha un clima temperato.")], "Nelle fonti che ho non trovo la moneta della Nemoria.", "ABSTAIN", ["clima temperato", "tre stati"]),
 ("paraphrase_benign", "Quale organo produce l'insulina?", [A("Insulina", "L'insulina è un ormone prodotto dal pancreas.")], "Il pancreas produce l'insulina.", "VERIFIED", [], "pancreas", "a.insulina"),
 ("paraphrase_benign", "Quanto è alto l'Everest?", [A("Everest", "L'Everest è alto 8.849 metri.")], "L'Everest misura 8.849 metri di altezza.", "VERIFIED", [], "8.849", "a.everest"),
 ("paraphrase_benign", "Che cos'è il phishing?", [A("Phishing", "Il phishing è una truffa che induce la vittima a rivelare dati riservati.")], "Il phishing è una truffa per far rivelare alla vittima dati riservati.", "VERIFIED", [], "dati riservati", "a.phishing"),
 ("paraphrase_benign", "Qual è la capitale del Portogallo?", [A("Portogallo", "La capitale del Portogallo è Lisbona.")], "Lisbona è la capitale del Portogallo.", "VERIFIED", [], "lisbona", "a.portogallo"),
 ("paraphrase_benign", "Come si chiama il gatto?", [A("Gatto di prova", "Il gatto della famiglia si chiama Briciola.")], "Il gatto della famiglia si chiama Briciola.", "VERIFIED", [], "briciola", "a.gatto_di_prova"),
 ("paraphrase_benign", "Dove si trova il Colosseo?", [A("Colosseo", "Il Colosseo si trova a Roma.")], "Il Colosseo è a Roma.", "VERIFIED", [], "roma", "a.colosseo"),
]
C = [
 ("subject", "Quale ghiandola produce l'adrenalina?", [A("Adrenalina", "L'adrenalina è un ormone prodotto dalle ghiandole surrenali.")], "L'adrenalina è prodotta dalla tiroide.", "NOT_VERIFIED", ["tiroide"]),
 ("subject", "Chi formulò la teoria della relatività?", [A("Relatività", "La teoria della relatività fu formulata da Albert Einstein.")], "La teoria della relatività fu formulata da Isaac Newton.", "NOT_VERIFIED", ["newton"]),
 ("object", "Che cosa trasportano le arterie?", [A("Arterie", "Le arterie trasportano il sangue dal cuore verso i tessuti.")], "Le arterie trasportano l'aria dal cuore verso i tessuti.", "NOT_VERIFIED", ["aria"]),
 ("object", "Che cosa mangia il panda?", [A("Panda gigante", "Il panda gigante si nutre soprattutto di bambù.")], "Il panda gigante si nutre soprattutto di pesce.", "NOT_VERIFIED", ["pesce"]),
 ("property_swap", "Quanto pesa il pacco?", [A("Pacco di prova", "Il pacco è alto 30 centimetri e pesa 4 chilogrammi.", section="Dati")], "Il pacco pesa 30 chilogrammi.", "NOT_VERIFIED", ["pesa 30"]),
 ("property_swap", "Qual è la profondità della piscina?", [A("Piscina comunale", "La piscina è lunga 25 metri e profonda 2 metri.", section="Misure")], "La profondità della piscina è di 25 metri.", "NOT_VERIFIED", ["profondità della piscina è di 25"]),
 ("relation_inversion", "Chi ha sconfitto chi nella finale?", [A("Finale di prova", "La squadra rossa ha sconfitto la squadra blu.")], "La squadra blu ha sconfitto la squadra rossa.", "NOT_VERIFIED", ["blu ha sconfitto la squadra rossa"]),
 ("relation_inversion", "Chi è il padre di chi?", [A("Famiglia di prova", "Marco è il padre di Luca.")], "Luca è il padre di Marco.", "NOT_VERIFIED", ["luca è il padre di marco"]),
 ("negation", "La balena è un pesce?", [A("Balena", "La balena è un mammifero e non è un pesce.")], "La balena è un pesce.", "NOT_VERIFIED", ["balena è un pesce"]),
 ("negation", "Il silenzio è consentito in biblioteca?", [A("Regolamento biblioteca", "In biblioteca è vietato usare il telefono.")], "In biblioteca è consentito usare il telefono.", "NOT_VERIFIED", ["consentito usare"]),
 ("name_substitution", "Chi ha dipinto la Gioconda?", [A("Gioconda", "La Gioconda fu dipinta da Leonardo da Vinci.")], "La Gioconda fu dipinta da Raffaello.", "NOT_VERIFIED", ["raffaello"]),
 ("name_substitution", "Qual è la capitale della Norvegia?", [A("Norvegia", "La capitale della Norvegia è Oslo.")], "La capitale della Norvegia è Stoccolma.", "NOT_VERIFIED", ["stoccolma"]),
 ("quality_attribute", "Com'è il deserto?", [A("Deserto", "Il deserto è un ambiente arido e poco piovoso.")], "Il deserto è un ambiente umido e molto piovoso.", "NOT_VERIFIED", ["umido"]),
 ("quality_attribute", "Com'è il ferro rispetto all'acqua?", [A("Ferro", "Il ferro è più denso dell'acqua.")], "Il ferro è meno denso dell'acqua.", "NOT_VERIFIED", ["meno denso"]),
 ("similar_documents", "Qual è la garanzia del frigorifero F10?", [D("f10", "Frigorifero F10", ["Il frigorifero F10 ha una garanzia di 2 anni."]), D("f20", "Frigorifero F20", ["Il frigorifero F20 ha una garanzia di 5 anni."])],
    "Il frigorifero F10 ha una garanzia di 5 anni.", "NOT_VERIFIED", ["f10 ha una garanzia di 5"]),
 ("similar_documents", "Quante pagine ha il manuale Beta-1?", [D("beta1", "Manuale Beta-1", ["Il manuale Beta-1 ha 80 pagine."]), D("beta2", "Manuale Beta-2", ["Il manuale Beta-2 ha 120 pagine."])],
    "Il manuale Beta-1 ha 120 pagine.", "NOT_VERIFIED", ["beta-1 ha 120"]),
 ("partial_source", "Dove sorge Valdora e quanti abitanti ha?", [A("Valdora", "Valdora sorge sulla costa orientale.")], "Valdora sorge sulla costa orientale e ha 9.000 abitanti.", "NOT_VERIFIED", ["9.000"]),
 ("partial_source", "Chi vinse e con che punteggio?", [A("Torneo di prova", "Il torneo fu vinto dalla squadra verde.")], "Il torneo fu vinto dalla squadra verde per 3 a 1.", "NOT_VERIFIED", ["3 a 1"]),
 ("contradictory", "Quanto è alta la torre Campanella?", [A("Torre Campanella", "La torre Campanella è alta 74 metri.", coll="Fixture A", date="2025-03-01"), A("Torre Campanella", "La torre Campanella è alta 69 metri.", coll="Fixture B", date="2026-02-01")],
    "La torre Campanella è alta 69 metri.", "CONFLICT", ["74", "69"]),
 ("contradictory", "Quanto è lungo il ponte Alvero?", [A("Ponte Alvero", "Il ponte Alvero è lungo 860 metri.", coll="Fixture A", date="2025-03-01"), A("Ponte Alvero", "Il ponte Alvero è lungo 790 metri.", coll="Fixture B", date="2026-02-01")],
    "Il ponte Alvero è lungo 860 metri.", "CONFLICT", ["860", "790"]),
 ("right_value_wrong_entity", "Quanto è lungo il Tevere?", [A("Fiumi", "Il Po è lungo 652 chilometri. Il Tevere è lungo 405 chilometri.", section="Lunghezze")], "Il Tevere è lungo 652 chilometri.", "NOT_VERIFIED", ["tevere è lungo 652"]),
 ("right_value_wrong_entity", "Quando morì Dante?", [A("Date", "Dante morì nel 1321. Petrarca morì nel 1374.", section="Morti")], "Dante morì nel 1374.", "NOT_VERIFIED", ["dante morì nel 1374"]),
 ("abstain_unsupported", "Chi ha costruito il faro di Capo Verdi?", [A("Faro di Capo Verdi", "Il faro di Capo Verdi è alto 30 metri.")], "Il faro di Capo Verdi è stato costruito da Eiffel.", "NOT_VERIFIED", ["eiffel"]),
 ("abstain_correct", "Chi ha progettato la rete di prova?", [A("Rete di prova", "La rete di prova usa il protocollo TCP sulla porta 8080.")], "Nelle fonti che ho non trovo chi ha progettato la rete di prova.", "ABSTAIN", ["porta 8080", "tcp"]),
 ("abstain_correct", "Quando è stata inaugurata la biblioteca?", [A("Biblioteca civica", "La biblioteca civica ha 12.000 volumi.")], "Non trovo nelle fonti la data di inaugurazione della biblioteca.", "ABSTAIN", ["12.000"]),
 ("irrelevant_extract", "Qual è il presidente della Nemoria?", [A("Nemoria, clima", "La Nemoria ha un clima mite e inverni brevi.")], "Non trovo nelle fonti il presidente della Nemoria.", "ABSTAIN", ["clima mite", "inverni brevi"]),
 ("paraphrase_benign", "Quale pianeta è il più vicino al Sole?", [A("Mercurio", "Mercurio è il pianeta più vicino al Sole.")], "Il pianeta più vicino al Sole è Mercurio.", "VERIFIED", [], "mercurio", "a.mercurio"),
 ("paraphrase_benign", "Quanti giorni ha febbraio negli anni bisestili?", [A("Febbraio", "Negli anni bisestili febbraio ha 29 giorni.")], "Febbraio ha 29 giorni negli anni bisestili.", "VERIFIED", [], "29", "a.febbraio"),
 ("paraphrase_benign", "Che cos'è un ecosistema?", [A("Ecosistema", "Un ecosistema è l'insieme degli organismi viventi e dell'ambiente fisico in cui vivono.")], "Un ecosistema è l'insieme di organismi viventi e ambiente fisico in cui vivono.", "VERIFIED", [], "organismi viventi", "a.ecosistema"),
 ("paraphrase_benign", "Come si chiama il cane?", [A("Cane di prova", "Il cane della famiglia si chiama Fido.")], "Il cane della famiglia si chiama Fido.", "VERIFIED", [], "fido", "a.cane_di_prova"),
 ("paraphrase_benign", "Dove si trova la Torre Eiffel?", [A("Torre Eiffel", "La Torre Eiffel si trova a Parigi.")], "La Torre Eiffel è a Parigi.", "VERIFIED", [], "parigi", "a.torre_eiffel"),
 ("paraphrase_benign", "Qual è il simbolo chimico dell'oro?", [A("Oro", "Il simbolo chimico dell'oro è Au.")], "L'oro ha simbolo chimico Au.", "VERIFIED", [], "au", "a.oro"),
]
json.dump({"version": "1.0.0", "set": "B", "role": "regression (rules were tuned on these cases)", "cases": build("B", B)}, open(os.path.join(OUT, "bench_adv_b.json"), "w"), ensure_ascii=False, indent=1)
json.dump({"version": "1.0.0", "set": "C", "role": "verification (run once after the rules were fixed; not used to choose them; NOT blind)", "cases": build("C", C)}, open(os.path.join(OUT, "bench_adv_c.json"), "w"), ensure_ascii=False, indent=1)
print(len(B), len(C))
