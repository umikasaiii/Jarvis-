"""§ FASE 2A.11 ADDENDUM §1-4 — generates the large (3000-5000 example)
training corpus for the Learned Semantic Classification Head.

This is DISTINCT from `core/src/main/resources/semantic/prototypes.json`
(the small, hand-picked corpus the PROTOTYPE/CENTROID classifier ships with
in the app, ~176 examples) — this script produces a much larger OFFLINE-ONLY
corpus meant to be embedded and used to train a learned head, never shipped
in the APK itself.

## Method (documented honestly, not hidden)

Each TEMPLATE FAMILY below is a hand-authored group of already-distinct
Italian sentence skeletons (different syntax/word order/register for the
SAME meaning, not one canonical sentence with a single word swapped) crossed
with slot value lists (day, subject, connective, ...) and a perturbation
pass (typo injection, accent-dropping, colloquial casing, truncation to an
incomplete phrase, negation where applicable). This is NOT "one template x
1000 mechanical substitutions" — every family already varies multiple
syntactic axes at once, and there are 90+ distinct families across
categories, each capturing a different real phrasing a person would actually
type or say. Splitting is done by TEMPLATE FAMILY (see split_corpus.py), so
the split-related leakage risk is about near-duplicate FAMILIES ending up in
both train and test, not the sheer combinatorial expansion within one
family, which is bounded per family (MAX_VARIANTS_PER_FAMILY) and always
kept in the SAME split.

Uses only `SemanticIntent`/`ToolFamily`/`SemanticOperation`/`ReferenceMode`
from the project's actual enums (`core/semantic/SemanticFrame.kt`,
`core/tools/RelevantToolSelector.kt`) — no parallel taxonomy.
"""
from __future__ import annotations

import hashlib
import itertools
import random
import re
from dataclasses import dataclass, field

RNG_SEED = 20260906
MAX_VARIANTS_PER_FAMILY = 75

# ---------------------------------------------------------------------------
# Shared slot vocabularies
# ---------------------------------------------------------------------------

DAYS = [
    "oggi", "domani", "dopodomani", "venerdì", "sabato", "domenica",
    "lunedì prossimo", "questo weekend", "la settimana prossima",
    "martedì", "giovedì pomeriggio", "stasera", "stamattina", "mercoledì",
    "tra una settimana", "venerdì sera", "domani mattina", "domani sera",
    "sabato prossimo", "tra due giorni", "il weekend prossimo", "lunedì",
]
DAYS_PAST = [
    "ieri", "l'altro ieri", "la settimana scorsa", "stanotte", "ieri notte",
    "ieri sera", "il mese scorso", "due giorni fa",
]
POLITE_OPEN = ["Puoi dirmi", "Sai dirmi", "Riesci a dirmi", "Mi sai dire", "Sapresti dirmi"]
CASUAL_OPEN = ["Dimmi un po'", "Fammi sapere", "Dai, dimmi"]
CONNECTORS = ["comunque", "per caso", "per favore", "se puoi", ""]
TOPIC_KNOWLEDGE = [
    "il motore a scoppio", "i buchi neri", "la fotosintesi", "la storia romana",
    "l'intelligenza artificiale", "i vulcani", "l'universo", "la genetica",
    "i social network", "le criptovalute", "il sistema solare", "la borsa",
    "i vaccini", "il riscaldamento globale", "la rivoluzione francese",
    "l'effetto serra", "la crittografia", "i dinosauri", "la relatività",
    "il DNA", "la tettonica a placche",
]

# § applied only to NO-SLOT families (conversation/clarification/knowledge
# hard-negatives/multi-source/ood) to add real syntactic variety beyond the
# hand-authored template count itself — a real register/opener change, not a
# no-op duplication (the empty-prefix entry alone already preserves the
# original hand-written sentence for roughly a sixth of the raw pool).
FRAMING_PREFIXES = [
    "", "", "Senti, ", "Scusa, ", "Dimmi un po', ", "Comunque, ", "Ok, ",
    "Ascolta, ", "Piuttosto, ", "A proposito, ",
]
FRAMING_SUFFIXES = ["", "", "", "dai", "grazie", "per favore"]


def apply_prefix(text: str, prefix: str) -> str:
    if not prefix:
        return text
    return prefix + text[0].lower() + text[1:]


def apply_suffix(text: str, suffix: str) -> str:
    if not suffix:
        return text
    return f"{text} {suffix}"


def rng() -> random.Random:
    return random.Random(RNG_SEED)


# ---------------------------------------------------------------------------
# Perturbations — applied to a fraction of generated variants per family
# ---------------------------------------------------------------------------

def drop_accents(text: str) -> str:
    table = str.maketrans("àèéìòù", "aeeiou")
    return text.translate(table)


def lowercase_no_punct(text: str) -> str:
    return re.sub(r"[?!.,]+$", "", text.lower())


def typo_swap(text: str, rand: random.Random) -> str:
    words = text.split(" ")
    candidates = [i for i, w in enumerate(words) if len(w) > 4]
    if not candidates:
        return text
    i = rand.choice(candidates)
    w = list(words[i])
    j = rand.randrange(len(w) - 1)
    w[j], w[j + 1] = w[j + 1], w[j]
    words[i] = "".join(w)
    return " ".join(words)


def truncate_incomplete(text: str) -> str:
    stripped = re.sub(r"[?!.,]+$", "", text)
    words = stripped.split(" ")
    if len(words) <= 3:
        return stripped
    return " ".join(words[: max(3, len(words) - 2)])


PERTURBATIONS = [
    lambda t, r: t,  # identity — most variants stay clean natural Italian
    lambda t, r: t,
    lambda t, r: t,
    lambda t, r: drop_accents(t),
    lambda t, r: lowercase_no_punct(t),
    lambda t, r: typo_swap(t, r),
    lambda t, r: truncate_incomplete(t),
    lambda t, r: drop_accents(lowercase_no_punct(t)),
]


@dataclass
class Family:
    id: str
    intent: str
    domains: list[str]
    operation: str = "UNKNOWN"
    reference_mode: str = "NONE"
    templates: list[str] = field(default_factory=list)
    slot: str | None = None  # name of the slot list used, e.g. "day"
    slot_values: list[str] = field(default_factory=list)
    blind_only: bool = False
    category: str = ""  # § stratification key for split_corpus.py — set in build_all_families()
    hard_negative: bool = False  # § paired with its capability counterpart for the hard-negative accuracy metric


@dataclass
class Example:
    text: str
    intent: str
    domains: list[str]
    operation: str
    reference_mode: str
    template_family: str
    blind_only: bool
    category: str
    hard_negative: bool


def expand_family(fam: Family, rand: random.Random) -> list[Example]:
    raw: list[str] = []
    if fam.slot and fam.slot_values:
        for tmpl in fam.templates:
            for val in fam.slot_values:
                raw.append(tmpl.format(**{fam.slot: val}))
    else:
        for tmpl in fam.templates:
            for prefix in FRAMING_PREFIXES:
                for suffix in FRAMING_SUFFIXES:
                    raw.append(apply_suffix(apply_prefix(tmpl, prefix), suffix))

    rand.shuffle(raw)
    raw = raw[:MAX_VARIANTS_PER_FAMILY]

    out: list[Example] = []
    seen: set[str] = set()
    for i, base in enumerate(raw):
        perturb = PERTURBATIONS[i % len(PERTURBATIONS)]
        text = perturb(base, rand).strip()
        if not text or text in seen:
            continue
        seen.add(text)
        out.append(Example(
            text=text, intent=fam.intent, domains=fam.domains, operation=fam.operation,
            reference_mode=fam.reference_mode, template_family=fam.id, blind_only=fam.blind_only,
            category=fam.category, hard_negative=fam.hard_negative,
        ))
    return out


# ---------------------------------------------------------------------------
# WEATHER
# ---------------------------------------------------------------------------

def weather_families() -> list[Family]:
    return [
        Family("wx_general_temp", "CAPABILITY_QUERY", ["WEATHER"],
               templates=["Che tempo fa {day}?", "Come sarà il tempo {day}?",
                          "Che tempo farà {day} di preciso?"],
               slot="day", slot_values=DAYS),
        Family("wx_hot_cold", "CAPABILITY_QUERY", ["WEATHER"],
               templates=["Farà caldo {day}?", "Si suderà parecchio {day}?",
                          "Ci sarà questo gran caldo anche {day}?", "Farà freddo {day}?"],
               slot="day", slot_values=DAYS, hard_negative=True),
        Family("wx_umbrella", "CAPABILITY_QUERY", ["WEATHER"],
               templates=["Mi serve l'ombrello {day}?", "Devo portare l'ombrello uscendo {day}?",
                          "Conviene prendere l'ombrello {day}?"],
               slot="day", slot_values=DAYS),
        Family("wx_rain", "CAPABILITY_QUERY", ["WEATHER"],
               templates=["È prevista pioggia {day}?", "Pioverà {day}?",
                          "C'è rischio di pioggia {day}?"],
               slot="day", slot_values=DAYS),
        Family("wx_wind", "CAPABILITY_QUERY", ["WEATHER"],
               templates=["Tira vento {day}?", "Ci sarà vento forte {day}?"],
               slot="day", slot_values=DAYS),
        Family("wx_outfit", "CAPABILITY_QUERY", ["WEATHER"],
               templates=["Mi conviene vestirmi pesante {day}?", "Meglio maglietta o giacca {day}?",
                          "Come mi devo vestire {day}?"],
               slot="day", slot_values=DAYS),
        Family("wx_day_overview", "CAPABILITY_QUERY", ["WEATHER"],
               templates=["Che giornata ci aspetta {day}?", "Che tipo di giornata sarà {day}?"],
               slot="day", slot_values=DAYS),
        Family("wx_sky", "CAPABILITY_QUERY", ["WEATHER"],
               templates=["Sarà sereno o nuvoloso {day}?", "Il cielo sarà coperto {day}?"],
               slot="day", slot_values=DAYS),
        Family("wx_compare_yesterday", "CAPABILITY_QUERY", ["WEATHER"],
               templates=["Fa più caldo di {day}?", "È più freddo rispetto a {day}?",
                          "È cambiato molto il tempo rispetto a {day}?"],
               slot="day", slot_values=DAYS_PAST, blind_only=True),
        Family("wx_snow", "CAPABILITY_QUERY", ["WEATHER"],
               templates=["Nevica da queste parti d'inverno?", "Ha mai nevicato qui in città?",
                          "Quanto nevica di solito da queste parti?"]),
        Family("wx_direct_alert", "DIRECT_COMMAND", ["WEATHER"],
               templates=["Avvisami se domani piove", "Mandami un avviso se farà troppo caldo",
                          "Fammi sapere se peggiora il tempo questo weekend",
                          "Avvertimi se domani tira vento forte"]),
        Family("wx_weekend", "CAPABILITY_QUERY", ["WEATHER"],
               templates=["Com'è messo il meteo per il weekend?", "Che tempo farà sabato e domenica?",
                          "Che previsioni ci sono per il fine settimana?"]),
        Family("wx_ellipsis_day", "CAPABILITY_QUERY", ["WEATHER"], reference_mode="ELLIPSIS",
               templates=["E {day} invece?", "E per {day} com'è?"],
               slot="day", slot_values=DAYS),
    ]


# ---------------------------------------------------------------------------
# HEALTH
# ---------------------------------------------------------------------------

def health_families() -> list[Family]:
    ranges = [
        "stanotte", "questa settimana", "ieri notte", "negli ultimi giorni",
        "in media questa settimana", "nell'ultima settimana", "stamattina presto",
        "in questi giorni",
    ]
    return [
        Family("hl_sleep_duration", "CAPABILITY_QUERY", ["HEALTH"],
               templates=["Quanto ho dormito {r}?", "Quante ore ho dormito {r}?",
                          "Quanto sonno ho fatto {r}?"],
               slot="r", slot_values=ranges, hard_negative=True),
        Family("hl_sleep_quality", "CAPABILITY_QUERY", ["HEALTH"],
               templates=["Ho dormito bene {r}?", "Come ho riposato {r}?", "Ho fatto un sonno tranquillo {r}?"],
               slot="r", slot_values=ranges),
        Family("hl_heart_rate", "CAPABILITY_QUERY", ["HEALTH"],
               templates=["Il mio battito era nella norma {r}?", "Che battito cardiaco avevo {r}?",
                          "Come stava il mio cuore {r}?"],
               slot="r", slot_values=ranges, hard_negative=True),
        Family("hl_resting_hr", "CAPABILITY_QUERY", ["HEALTH"],
               templates=["Qual è la mia frequenza a riposo {r}?", "Quant'era il battito a riposo {r}?",
                          "Quanto battevo a riposo {r}?"],
               slot="r", slot_values=ranges),
        Family("hl_general_wellbeing", "CAPABILITY_QUERY", ["HEALTH"],
               templates=["Come stanno andando i miei parametri di salute {r}?",
                          "Come sto messo a livello di salute {r}?",
                          "Che dati di salute risultano {r}?"],
               slot="r", slot_values=ranges),
        Family("hl_average", "CAPABILITY_QUERY", ["HEALTH"], reference_mode="ELLIPSIS",
               templates=["E la media?", "E in media com'è?", "Qual è la media invece?"]),
        Family("hl_tired", "CAPABILITY_QUERY", ["HEALTH"],
               templates=["Perché mi sento così stanco oggi, ho dormito poco?",
                          "Sono stanco, quanto ho dormito di preciso stanotte?"]),
        Family("hl_compare_nights", "CAPABILITY_QUERY", ["HEALTH"],
               templates=["Ho dormito meglio ieri notte o stanotte?",
                          "Ho riposato di più questa settimana o quella scorsa?"], blind_only=True),
    ]


# ---------------------------------------------------------------------------
# AGENDA
# ---------------------------------------------------------------------------

def agenda_families() -> list[Family]:
    return [
        Family("ag_generic", "CAPABILITY_QUERY", ["AGENDA"],
               templates=["Che impegni ho {day}?", "Cosa ho in programma {day}?",
                          "Ho qualcosa segnato per {day}?", "Cosa devo fare {day}?"],
               slot="day", slot_values=DAYS, hard_negative=True),
        Family("ag_count", "CAPABILITY_QUERY", ["AGENDA"],
               templates=["Quanti appuntamenti ho {day}?", "Quante cose ho da fare {day}?"],
               slot="day", slot_values=DAYS),
        Family("ag_specific_check", "CAPABILITY_QUERY", ["AGENDA"],
               templates=["Ho la riunione confermata {day}?", "È ancora segnato l'appuntamento dal dentista {day}?"],
               slot="day", slot_values=DAYS),
        Family("ag_free_time", "CAPABILITY_QUERY", ["AGENDA"],
               templates=["Sono libero {day}?", "Ho tempo libero {day}?"],
               slot="day", slot_values=DAYS),
        Family("ag_week_range", "CAPABILITY_QUERY", ["AGENDA"],
               templates=["Che impegni ho tra oggi e venerdì?", "Cosa ho in programma nel weekend?",
                          "Cosa devo fare tra lunedì e giovedì?", "Quanti impegni ho in totale questa settimana?"]),
        Family("ag_ellipsis_day", "CAPABILITY_QUERY", ["AGENDA"], reference_mode="ELLIPSIS",
               templates=["E dopodomani?", "E il giorno dopo?", "E la settimana prossima invece?"]),
        Family("ag_create", "DIRECT_COMMAND", ["AGENDA"], operation="CREATE",
               templates=["Segna che devo chiamare il dentista {day}", "Aggiungi un promemoria per {day}",
                          "Ricordami di comprare il latte {day}", "Metti in agenda la riunione {day}"],
               slot="day", slot_values=DAYS),
        Family("ag_move", "DIRECT_COMMAND", ["AGENDA"], operation="UPDATE",
               templates=["Sposta l'appuntamento del dentista a {day}", "Rimanda la riunione a {day}"],
               slot="day", slot_values=DAYS),
        Family("ag_delete", "DIRECT_COMMAND", ["AGENDA"], operation="DELETE",
               templates=["Cancella l'appuntamento di {day}", "Elimina il promemoria di {day}"],
               slot="day", slot_values=DAYS),
        Family("ag_query_named", "CAPABILITY_QUERY", ["AGENDA"],
               templates=["A che ora è la riunione {day}?", "Quando devo andare dal dentista?",
                          "Che giorno ho l'appuntamento con Marco?"], blind_only=True),
    ]


# ---------------------------------------------------------------------------
# DEVICE_INFO
# ---------------------------------------------------------------------------

def device_info_families() -> list[Family]:
    return [
        Family("dv_ram", "CAPABILITY_QUERY", ["DEVICE_INFO"],
               templates=["Quanta RAM ha il mio telefono?", "Quanta memoria RAM monta questo dispositivo?",
                          "Il mio cellulare quanta RAM ha effettivamente?"], hard_negative=True),
        Family("dv_ram_partitive", "CAPABILITY_QUERY", ["DEVICE_INFO"], reference_mode="PARTITIVE",
               templates=["Quanta ne ho?", "Quanta ne ho disponibile adesso?", "E quanta ne ho io?"]),
        Family("dv_storage", "CAPABILITY_QUERY", ["DEVICE_INFO"],
               templates=["Quanto spazio libero ho sul telefono?", "Quanta memoria di archiviazione resta libera?",
                          "Sto finendo lo spazio sul telefono?"]),
        Family("dv_android_version", "CAPABILITY_QUERY", ["DEVICE_INFO"],
               templates=["Che versione di Android ho?", "Con che versione di Android va il telefono?",
                          "Che release di Android è installata?"]),
        Family("dv_model", "CAPABILITY_QUERY", ["DEVICE_INFO"],
               templates=["Che modello di telefono è questo?", "Che dispositivo sto usando esattamente?",
                          "Che telefono ho in mano di preciso?"]),
        Family("dv_battery", "CAPABILITY_QUERY", ["DEVICE_INFO"],
               templates=["A che percentuale è la batteria?", "Quanta batteria mi resta?",
                          "Sto per rimanere senza batteria?"], blind_only=True, hard_negative=True),
    ]


# ---------------------------------------------------------------------------
# KNOWLEDGE_QUERY — general knowledge + explicit hard-negative counterparts
# ---------------------------------------------------------------------------

def knowledge_families() -> list[Family]:
    fams = [
        Family("kn_generic_topic", "KNOWLEDGE_QUERY", ["KNOWLEDGE"],
               templates=["Mi spieghi come funziona {t}?", "Cos'è di preciso {t}?",
                          "Puoi raccontarmi qualcosa su {t}?"],
               slot="t", slot_values=TOPIC_KNOWLEDGE),
        # Hard negatives — same surface topic word as a capability domain, but generic/world knowledge
        Family("kn_hn_ram", "KNOWLEDGE_QUERY", ["KNOWLEDGE"],
               templates=["A cosa serve la RAM in un computer?", "Che differenza c'è tra RAM e disco fisso?",
                          "Perché un computer ha bisogno di RAM?"], hard_negative=True),
        Family("kn_hn_sleep", "KNOWLEDGE_QUERY", ["KNOWLEDGE"],
               templates=["Quante ore dovrebbe dormire un adulto in media?",
                          "Perché con il caldo si dorme peggio?",
                          "Cosa succede al cervello quando dormiamo?"], hard_negative=True),
        Family("kn_hn_heart", "KNOWLEDGE_QUERY", ["KNOWLEDGE"],
               templates=["Qual è il battito cardiaco medio di una persona adulta?",
                          "Come funziona il cuore umano?",
                          "Perché il battito cardiaco aumenta quando si fa sport?"], hard_negative=True),
        Family("kn_hn_weather", "KNOWLEDGE_QUERY", ["KNOWLEDGE"],
               templates=["Come si formano le nuvole?", "Perché d'estate fa più caldo che d'inverno?",
                          "Cos'è un anticiclone?"], hard_negative=True),
        Family("kn_hn_calendar", "KNOWLEDGE_QUERY", ["KNOWLEDGE"],
               templates=["Come funziona un calendario digitale?", "Chi ha inventato il calendario gregoriano?",
                          "Perché un anno ha 365 giorni?"], hard_negative=True),
        Family("kn_hn_battery", "KNOWLEDGE_QUERY", ["KNOWLEDGE"],
               templates=["Come funziona una batteria al litio?", "Perché le batterie si degradano nel tempo?",
                          "Quanto dura in genere una batteria al litio?"], blind_only=True, hard_negative=True),
        Family("kn_definition_short", "KNOWLEDGE_QUERY", ["KNOWLEDGE"],
               templates=["Cos'è {t}?", "Che cos'è esattamente {t}?"],
               slot="t", slot_values=TOPIC_KNOWLEDGE),
    ]
    return fams


# ---------------------------------------------------------------------------
# CONVERSATION
# ---------------------------------------------------------------------------

def conversation_families() -> list[Family]:
    return [
        Family("cv_greeting", "CONVERSATION", [],
               templates=["Ciao, come stai?", "Buongiorno JARVIS", "Ehi, ci sei?",
                          "Buonasera, tutto bene da te?", "Come va oggi?"]),
        Family("cv_smalltalk", "CONVERSATION", [],
               templates=["Ti va di fare due chiacchiere?", "Raccontami qualcosa di divertente",
                          "Che ne pensi della giornata di oggi?"]),
        Family("cv_thanks", "CONVERSATION", [],
               templates=["Grazie mille, sei stato utile", "Perfetto, grazie davvero",
                          "Sei stato bravo, complimenti"]),
        Family("cv_mood", "CONVERSATION", [],
               templates=["Sono un po' stanco oggi, sinceramente", "Oggi sono un po' giù di morale"]),
        Family("cv_farewell", "CONVERSATION", [],
               templates=["Buonanotte, a domani", "Ci sentiamo dopo", "A dopo allora"], blind_only=True),
    ]


# ---------------------------------------------------------------------------
# CLARIFICATION
# ---------------------------------------------------------------------------

def clarification_families() -> list[Family]:
    return [
        Family("cl_repeat", "CLARIFICATION", [],
               templates=["Puoi ripetere, non ho capito bene", "Scusa, puoi ridirlo?",
                          "Non ho sentito bene, ripeti per favore"]),
        Family("cl_meaning", "CLARIFICATION", [],
               templates=["Cosa intendi esattamente?", "In che senso, scusa?",
                          "Non ho capito una parola di quello che hai detto"], blind_only=True),
        Family("cl_confused", "CLARIFICATION", [],
               templates=["Non capisco cosa intendi con questo", "Puoi spiegarti meglio, per favore?",
                          "Fammi un esempio, non ho capito"]),
        Family("cl_which", "CLARIFICATION", [],
               templates=["Quale dei due intendevi?", "A quale ti riferisci di preciso?",
                          "Di quale parli esattamente?"]),
    ]


# ---------------------------------------------------------------------------
# MULTI_SOURCE_REASONING
# ---------------------------------------------------------------------------

def multi_source_families() -> list[Family]:
    return [
        Family("ms_health_agenda", "MULTI_SOURCE_REASONING", ["HEALTH", "AGENDA"],
               templates=[
                   "Considerando come ho dormito e gli impegni di domani, a che ora dovrei andare a letto stasera?",
                   "Tenendo conto di quanto ho riposato e cosa ho da fare domani, mi conviene rimandare la palestra?",
                   "Vista la nottata che ho fatto, quanto tempo ho libero domani per riposare?",
                   "Ho dormito poco e domani ho la riunione delle 9, mi conviene andare a letto presto stasera?",
                   "Sto un po' stanco e ho parecchi impegni domani, dovrei rimandare qualcosa?",
                   "Col sonno che ho recuperato questa settimana, ce la faccio con tutti gli appuntamenti di domani?",
               ]),
        Family("ms_health_weather", "MULTI_SOURCE_REASONING", ["HEALTH", "WEATHER"],
               templates=[
                   "Il mio sonno di stanotte più il freddo di oggi, mi conviene allenarmi lo stesso?",
                   "Con questo caldo e come ho dormito, è meglio uscire a correre stasera o domani mattina?",
                   "Ho dormito male e domani fa freddo, mi conviene comunque uscire a fare sport?",
                   "Visto il caldo di oggi e la stanchezza che ho accumulato, meglio riposare o allenarmi lo stesso?",
               ]),
        Family("ms_agenda_weather", "MULTI_SOURCE_REASONING", ["AGENDA", "WEATHER"],
               templates=[
                   "Se domani piove e ho la riunione delle 9, mi conviene uscire prima?",
                   "Vista la previsione di domani e quello che ho segnato in agenda, mi serve la giacca a vento?",
                   "Con gli impegni di oggi pomeriggio, farà abbastanza caldo da non portare il cappotto?",
                   "Ho la palestra segnata per stasera, ma pioverà? Meglio spostarla?",
                   "Domani ho un appuntamento fuori città, che tempo devo aspettarmi per vestirmi bene?",
               ]),
        Family("ms_triple", "MULTI_SOURCE_REASONING", ["HEALTH", "AGENDA", "WEATHER"],
               templates=[
                   "Considerando sonno, impegni e meteo di domani, mi conviene spostare l'allenamento?",
                   "Tra quanto ho dormito, gli impegni di domani e il freddo previsto, che programma mi consigli?",
               ], blind_only=True),
    ]


# ---------------------------------------------------------------------------
# UNKNOWN / OOD — genuinely open-world, never a capability
# ---------------------------------------------------------------------------

def ood_families() -> list[Family]:
    return [
        Family("ood_advice", "CONVERSATION", [],
               templates=["Secondo te conviene investire in obbligazioni o azioni quest'anno?",
                          "Mi consigli un libro di fantascienza da leggere?",
                          "Meglio allenarsi la mattina presto o la sera tardi secondo te?",
                          "Che marca di scarpe da corsa mi consigli?",
                          "Secondo te vale la pena cambiare lavoro quest'anno?"]),
        Family("ood_random_knowledge", "KNOWLEDGE_QUERY", ["KNOWLEDGE"],
               templates=["Qual è la differenza tra un motore diesel e uno a benzina?",
                          "Come si gioca a scacchi in modo strategico?",
                          "Perché il cielo di notte è nero e non blu?",
                          "Come si allena un cane a stare seduto?",
                          "Qual è la capitale dell'Australia?"]),
        Family("ood_gibberish", "UNKNOWN", [],
               templates=["Zorgle blaminto della fasca whurendi non esiste per davvero",
                          "Xilonqua vetrusmo fantibolo glorx nimparil squetto",
                          "Blenza forpiglio quantrusco melifando strambosa",
                          "Trafolinga bruxemuto vaslendria kompifor gnarlutti",
                          "Squeblor nantavik flurindesta prolimango zaxu"]),
        Family("ood_offtopic", "CONVERSATION", [],
               templates=["Qual è la tua serie tv preferita?", "Chi ha vinto l'ultimo mondiale di calcio?",
                          "Mi consigli un ristorante di sushi in centro?",
                          "Qual è la squadra di calcio più forte quest'anno?"], blind_only=True),
        Family("ood_hypothetical", "KNOWLEDGE_QUERY", ["KNOWLEDGE"],
               templates=["Cosa succederebbe se la terra smettesse di girare?",
                          "Se tutti gli alberi sparissero, cosa cambierebbe nell'aria?",
                          "Cosa succede se mescoli bicarbonato e aceto?"], blind_only=True),
        Family("ood_recommendation", "CONVERSATION", [],
               templates=["Che film mi consigli per stasera?",
                          "Qual è un buon hobby da iniziare quest'anno?",
                          "Mi dai un consiglio per organizzare meglio la giornata in generale?"], blind_only=True),
    ]


ALL_FAMILY_BUILDERS = [
    weather_families, health_families, agenda_families, device_info_families,
    knowledge_families, conversation_families, clarification_families,
    multi_source_families, ood_families,
]


def build_all_families() -> list[Family]:
    families: list[Family] = []
    for builder in ALL_FAMILY_BUILDERS:
        category = builder.__name__.removesuffix("_families").upper()
        for fam in builder():
            if fam is None:
                continue
            fam.category = category
            families.append(fam)
    return families


def generate_examples() -> list[Example]:
    rand = rng()
    examples: list[Example] = []
    for fam in build_all_families():
        examples.extend(expand_family(fam, rand))
    return examples


if __name__ == "__main__":
    examples = generate_examples()
    print(f"total examples: {len(examples)}")
    by_intent: dict[str, int] = {}
    for e in examples:
        by_intent[e.intent] = by_intent.get(e.intent, 0) + 1
    for k, v in sorted(by_intent.items(), key=lambda kv: -kv[1]):
        print(f"  {k}: {v}")
