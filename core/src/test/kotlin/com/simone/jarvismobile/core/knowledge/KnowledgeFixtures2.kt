package com.simone.jarvismobile.core.knowledge

import com.simone.jarvismobile.core.knowledge.zim.CountingSource
import com.simone.jarvismobile.core.knowledge.zim.TestDecompressor
import com.simone.jarvismobile.core.knowledge.zim.TestZimBuilder
import com.simone.jarvismobile.core.knowledge.zim.TitleLayout
import com.simone.jarvismobile.core.knowledge.zim.ZimArchive
import com.simone.jarvismobile.core.knowledge.zim.ZimKnowledgeSource

/**
 * SIMULATED SOURCES, second generation (KNOWLEDGE ENGINE 2.0): shaped like the archives actually on the Honor 200 — a
 * Wikipedia-like encyclopedia, a Wiktionary-like DICTIONARY (lexical entries, no encyclopaedic facts), a Wikimed-like
 * medical archive, a Wikibooks-like how-to archive and an OLD encyclopedia — all in the MODERN ZIM layout. Written for these
 * tests; the facts were checked by hand. They say nothing about the coverage of the real archives.
 */
object KnowledgeFixtures2 {
    private fun t(path: String, title: String, lead: String) = Triple(path, title, lead)

    val enc = listOf(
        t("Polonia", "Polonia", "La Polonia è uno Stato dell'Europa centrale. La capitale è Varsavia. Ha una superficie di circa 312.696 km². La lingua ufficiale è il polacco. La popolazione è di circa 37,6 milioni di abitanti (2022)."),
        t("Ungheria", "Ungheria", "L'Ungheria è uno Stato dell'Europa centrale. La capitale è Budapest. Ha una superficie di circa 93.030 km². La lingua ufficiale è l'ungherese."),
        t("Svezia", "Svezia", "La Svezia è uno Stato dell'Europa settentrionale. La capitale è Stoccolma. Ha una superficie di circa 450.295 km². Si parla lo svedese."),
        t("Norvegia", "Norvegia", "La Norvegia è uno Stato dell'Europa settentrionale. La capitale è Oslo. Ha una superficie di circa 385.207 km². Si parla il norvegese."),
        t("Turchia", "Turchia", "La Turchia è uno Stato tra l'Europa e l'Asia. La capitale è Ankara. Ha una superficie di circa 783.562 km². La lingua ufficiale è il turco. La popolazione è di circa 85 milioni di abitanti (2023)."),
        t("Argentina", "Argentina", "L'Argentina è uno Stato dell'America meridionale. La capitale è Buenos Aires. Ha una superficie di circa 2,78 milioni di km². La lingua ufficiale è lo spagnolo."),
        t("Messico", "Messico", "Il Messico è uno Stato dell'America settentrionale. La capitale è Città del Messico. Ha una superficie di circa 1,96 milioni di km². Si parla lo spagnolo."),
        t("India", "India", "L'India è uno Stato dell'Asia meridionale. La capitale è Nuova Delhi. Ha una superficie di circa 3,29 milioni di km². Le lingue ufficiali sono l'hindi e l'inglese."),
        t("Cina", "Cina", "La Cina è uno Stato dell'Asia orientale. La capitale è Pechino. Ha una superficie di circa 9,6 milioni di km². La lingua più parlata è il cinese mandarino."),
        t("Marocco", "Marocco", "Il Marocco è uno Stato dell'Africa nordoccidentale. La capitale è Rabat. Ha una superficie di circa 446.550 km². Le lingue ufficiali sono l'arabo e il berbero."),
        t("Danubio", "Danubio", "Il Danubio è un fiume lungo circa 2.850 km, il secondo d'Europa per lunghezza. Sfocia nel mar Nero."),
        t("Reno", "Reno", "Il Reno è un fiume lungo circa 1.230 km che nasce nelle Alpi svizzere e sfocia nel mare del Nord."),
        t("Tamigi", "Tamigi", "Il Tamigi è un fiume dell'Inghilterra lungo circa 346 km; attraversa Londra."),
        t("Everest", "Everest", "L'Everest è la montagna più alta della Terra, con 8.849 metri di altezza sul livello del mare. Si trova nell'Himalaya, al confine tra Nepal e Cina."),
        t("K2", "K2", "Il K2 è la seconda montagna più alta della Terra, con 8.611 metri di altezza, nel Karakorum."),
        t("Cervino", "Cervino", "Il Cervino è una montagna delle Alpi alta 4.478 metri, al confine tra Italia e Svizzera."),
        t("Rivoluzione_russa", "Rivoluzione russa", "La Rivoluzione russa del 1917 portò alla caduta dello zar Nicola II e alla nascita dell'Unione Sovietica nel 1922."),
        t("Caduta_di_Costantinopoli", "Caduta di Costantinopoli", "Costantinopoli fu conquistata dai turchi ottomani il 29 maggio 1453, segnando la fine dell'Impero romano d'Oriente."),
        t("Battaglia_di_Waterloo", "Battaglia di Waterloo", "La battaglia di Waterloo si combatté il 18 giugno 1815 e segnò la sconfitta definitiva di Napoleone Bonaparte."),
        t("Sbarco_sulla_Luna", "Sbarco sulla Luna", "Il 20 luglio 1969 gli astronauti Neil Armstrong e Buzz Aldrin sbarcarono sulla Luna con la missione Apollo 11."),
        t("Mitocondrio", "Mitocondrio", "Il mitocondrio è un organello delle cellule eucariote che produce energia sotto forma di ATP."),
        t("Penicillina", "Penicillina", "La penicillina è il primo antibiotico scoperto, individuato da Alexander Fleming nel 1928."),
        t("Tavola_periodica", "Tavola periodica", "La tavola periodica classifica 118 elementi chimici conosciuti in ordine di numero atomico crescente."),
        t("Transistor", "Transistor", "Il transistor è un componente elettronico a semiconduttore inventato nel 1947 nei laboratori Bell."),
        t("Laser", "Laser", "Il laser è un dispositivo che emette un fascio di luce coerente e concentrata. Il primo laser funzionante fu realizzato nel 1960."),
        t("GPS", "GPS", "Il GPS è un sistema di posizionamento satellitare gestito dagli Stati Uniti; usa satelliti in orbita a circa 20.200 km di quota."),
        t("Mercurio_(pianeta)", "Mercurio (pianeta)", "Mercurio è il pianeta più vicino al Sole, a una distanza media di circa 57,9 milioni di km. Compie un'orbita completa in 88 giorni."),
        t("Mercurio_(elemento)", "Mercurio (elemento)", "Il mercurio è un elemento chimico con simbolo Hg e numero atomico 80. È l'unico metallo liquido a temperatura ambiente."),
        t("Georgia_(Stato)", "Georgia (Stato)", "La Georgia è uno Stato del Caucaso. La capitale è Tbilisi."),
        t("Georgia_(Stati_Uniti)", "Georgia (Stati Uniti)", "La Georgia è uno Stato degli Stati Uniti d'America. La capitale è Atlanta."),
    )

    /** Wiktionary-like: lexical entries (definitions, translations); NO encyclopaedic facts such as "the capital of X is Y". */
    val dict = listOf(
        t("capitale", "capitale", "capitale sostantivo maschile: l'insieme dei beni economici impiegati in un'attività produttiva. capitale sostantivo femminile: la città in cui hanno sede gli organi di governo di uno Stato. capitale aggettivo: che riguarda la testa; principale, fondamentale. Traduzioni: inglese capital; francese capitale; tedesco Hauptstadt."),
        t("Polonia", "Polonia", "Polonia nome proprio femminile: Stato dell'Europa centrale. Etimologia: dal nome della tribù dei Polani. Traduzioni: inglese Poland; francese Pologne."),
        t("Portogallo", "Portogallo", "Portogallo nome proprio maschile: Stato dell'Europa sudoccidentale. Etimologia: dal latino Portus Cale. Traduzioni: inglese Portugal; francese Portugal."),
        t("Turchia", "Turchia", "Turchia nome proprio femminile: Stato tra l'Europa e l'Asia. Etimologia: dal persiano Turkiya."),
        t("Danubio", "Danubio", "Danubio nome proprio maschile: fiume dell'Europa centrale."),
        t("Lisbona", "Lisbona", "Lisbona nome proprio femminile: città sul fiume Tago."),
        t("fiume", "fiume", "fiume sostantivo maschile: grande corso d'acqua che sfocia nel mare o in un lago. Traduzioni: inglese river."),
        t("lingua", "lingua", "lingua sostantivo femminile: organo muscolare della bocca; sistema di comunicazione di una comunità. Traduzioni: inglese language, tongue."),
        t("altezza", "altezza", "altezza sostantivo femminile: la dimensione verticale di un corpo; titolo onorifico dei principi."),
        t("montagna", "montagna", "montagna sostantivo femminile: grande rilievo del terreno. Traduzioni: inglese mountain."),
        t("popolazione", "popolazione", "popolazione sostantivo femminile: l'insieme degli abitanti di un luogo."),
        t("superficie", "superficie", "superficie sostantivo femminile: la parte esterna di un corpo; l'estensione di una figura piana."),
        t("moneta", "moneta", "moneta sostantivo femminile: pezzo di metallo coniato usato come mezzo di pagamento."),
        t("insulina", "insulina", "insulina sostantivo femminile: ormone prodotto dal pancreas che regola la concentrazione di glucosio nel sangue."),
        t("guerra", "guerra", "guerra sostantivo femminile: conflitto armato tra Stati o gruppi."),
        t("anno", "anno", "anno sostantivo maschile: periodo di dodici mesi, corrispondente a una rivoluzione della Terra attorno al Sole."),
    )

    val med = listOf(
        t("Diabete", "Diabete", "Il diabete mellito è una malattia metabolica caratterizzata da elevati livelli di glucosio nel sangue. Il tipo 1 è dovuto alla carenza di insulina; il tipo 2 alla ridotta risposta all'insulina."),
        t("Ipertensione", "Ipertensione", "L'ipertensione arteriosa è l'aumento persistente della pressione del sangue nelle arterie. Si parla di ipertensione quando la pressione supera 140/90 mmHg."),
        t("Anemia", "Anemia", "L'anemia è una riduzione dell'emoglobina nel sangue. I sintomi comprendono stanchezza e pallore."),
        t("Asma", "Asma", "L'asma è una malattia infiammatoria cronica delle vie respiratorie. I sintomi comprendono respiro sibilante, tosse e senso di oppressione al petto."),
        t("Pancreas", "Pancreas", "Il pancreas è una ghiandola dell'addome che produce l'insulina e gli enzimi digestivi."),
        t("Insulina", "Insulina", "L'insulina è un ormone prodotto dalle cellule beta del pancreas che abbassa la concentrazione di glucosio nel sangue."),
        t("Paracetamolo", "Paracetamolo", "Il paracetamolo è un farmaco antipiretico e analgesico, privo di azione antinfiammatoria significativa."),
        t("Ibuprofene", "Ibuprofene", "L'ibuprofene è un farmaco antinfiammatorio non steroideo con azione analgesica e antipiretica."),
    )

    val books = listOf(
        t("Cambiare_una_gomma_della_bicicletta", "Cambiare una gomma della bicicletta", "Per cambiare la camera d'aria si smonta la ruota, si sfila il copertone con le leve e si sostituisce la camera forata. Poi si rimonta il copertone e si gonfia a pressione adeguata."),
        t("Introduzione_a_Python", "Introduzione a Python", "Python è un linguaggio di programmazione interpretato. Una variabile si crea assegnando un valore con il segno uguale."),
        t("Fare_il_pane", "Fare il pane", "Per fare il pane servono farina, acqua, lievito e sale. L'impasto va lasciato lievitare per circa due ore."),
    )

    val oldEnc = listOf(
        t("Polonia", "Polonia", "La Polonia è uno Stato dell'Europa centrale. La capitale è Varsavia. La popolazione è di circa 38,2 milioni di abitanti (2002)."),
        t("Turchia", "Turchia", "La Turchia è uno Stato tra l'Europa e l'Asia. La capitale è Ankara. La popolazione è di circa 74 milioni di abitanti (2011)."),
    )

    private var seed = 1
    private fun source(title: String, date: String, arts: List<Triple<String, String, String>>, name: String? = null): ZimKnowledgeSource {
        val b = TestZimBuilder().apply { compression = 5; entriesPerCluster = 6; titleLayout = TitleLayout.MODERN; uuidSeed = ++seed * 7 }
        arts.forEach { (path, name, lead) -> b.article(path, name, "<html><head><title>$name</title></head><body><h1>$name</h1><p>$lead</p></body></html>") }
        b.metadata("Title", title).metadata("Date", date).metadata("Language", "ita")
        name?.let { b.metadata("Name", it) } // real Kiwix archives carry e.g. "wiktionary_it_all_nopic"
        return ZimKnowledgeSource(ZimArchive.open(CountingSource(b.build()), TestDecompressor))
    }

    fun encyclopedia() = source("Enciclopedia", "2026-05-10", enc, name = "wikipedia_it_all_nopic")
    fun dictionary() = source("Wikizionario", "2026-07-01", dict, name = "wiktionary_it_all_nopic")
    fun medicine() = source("Wikimed", "2026-06-15", med)
    fun howTo() = source("Wikibooks", "2026-07-01", books)
    fun oldEncyclopedia() = source("Enciclopedia (vecchia)", "2012-03-01", oldEnc)

    /** What the user has on the phone TODAY: no Wikipedia. */
    fun phoneToday() = listOf(dictionary(), medicine(), howTo())
    fun withEncyclopedia() = listOf(encyclopedia(), dictionary(), medicine(), howTo())
}
