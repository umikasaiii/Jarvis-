package com.simone.jarvismobile.core.knowledge

import com.simone.jarvismobile.core.knowledge.zim.TestDecompressor
import com.simone.jarvismobile.core.knowledge.zim.CountingSource
import com.simone.jarvismobile.core.knowledge.zim.TestZimBuilder
import com.simone.jarvismobile.core.knowledge.zim.ZimArchive
import com.simone.jarvismobile.core.knowledge.zim.ZimKnowledgeSource

/**
 * SIMULATED SOURCES: a small Italian mini-encyclopedia written for these tests (leads of a few sentences, facts checked
 * by hand). It exists to measure RETRIEVAL and GROUNDING behaviour; it is not shipped and says nothing about a real
 * Wikipedia archive's coverage.
 */
object KnowledgeFixtures {
    private val articles: List<Triple<String, String, String>> = listOf(
        // countries
        Triple("Francia", "Francia", "La Francia è uno Stato dell'Europa occidentale. La capitale è Parigi. Ha una superficie di circa 551.695 km² nella parte metropolitana. La lingua ufficiale è il francese."),
        Triple("Italia", "Italia", "L'Italia è una repubblica dell'Europa meridionale. La capitale è Roma. La superficie è di circa 301.340 km². La lingua ufficiale è l'italiano. La popolazione è di circa 58,9 milioni di abitanti (2023)."),
        Triple("Spagna", "Spagna", "La Spagna è uno Stato dell'Europa sudoccidentale. La capitale è Madrid. Ha una superficie di circa 505.990 km². La lingua ufficiale è lo spagnolo, detto anche castigliano."),
        Triple("Germania", "Germania", "La Germania è uno Stato dell'Europa centrale. La capitale è Berlino. Ha una superficie di circa 357.600 km². Si parla il tedesco."),
        Triple("Portogallo", "Portogallo", "Il Portogallo è uno Stato dell'Europa sudoccidentale. La capitale è Lisbona. Ha una superficie di circa 92.212 km². La lingua ufficiale è il portoghese."),
        Triple("Grecia", "Grecia", "La Grecia è uno Stato dell'Europa sudorientale. La capitale è Atene. Ha una superficie di circa 131.957 km². Si parla il greco."),
        Triple("Regno_Unito", "Regno Unito", "Il Regno Unito è uno Stato dell'Europa nordoccidentale. La capitale è Londra. Ha una superficie di circa 243.610 km². La lingua più parlata è l'inglese."),
        Triple("Giappone", "Giappone", "Il Giappone è uno Stato insulare dell'Asia orientale. La capitale è Tokyo. Ha una superficie di circa 377.975 km². Si parla il giapponese."),
        Triple("Egitto", "Egitto", "L'Egitto è uno Stato dell'Africa nordorientale. La capitale è Il Cairo. Ha una superficie di circa 1.002.450 km². La lingua ufficiale è l'arabo. Attraversato dal Nilo."),
        Triple("Brasile", "Brasile", "Il Brasile è il più grande Stato dell'America del Sud. La capitale è Brasilia. Ha una superficie di circa 8,5 milioni di km². La lingua ufficiale è il portoghese."),
        Triple("Canada", "Canada", "Il Canada è uno Stato dell'America settentrionale. La capitale è Ottawa. Ha una superficie di circa 9,98 milioni di km². Le lingue ufficiali sono l'inglese e il francese."),
        Triple("Australia", "Australia", "L'Australia è uno Stato dell'Oceania. La capitale è Canberra. Ha una superficie di circa 7,69 milioni di km². La lingua ufficiale de facto è l'inglese."),
        // geography
        Triple("Nilo", "Nilo", "Il Nilo è un fiume dell'Africa nordorientale, lungo circa 6.650 km. Sfocia nel mar Mediterraneo."),
        Triple("Monte_Bianco", "Monte Bianco", "Il Monte Bianco è la montagna più alta delle Alpi, con 4.806 metri di altezza. Si trova al confine tra Italia e Francia."),
        Triple("Asia", "Asia", "L'Asia è il continente più esteso della Terra, con una superficie di circa 44,58 milioni di km²."),
        Triple("Oceano_Pacifico", "Oceano Pacifico", "L'oceano Pacifico è il più grande oceano della Terra, con una superficie di circa 165 milioni di km²."),
        Triple("Po", "Po", "Il Po è il fiume più lungo d'Italia, con 652 km di lunghezza. Scorre nella pianura Padana e sfocia nell'Adriatico."),
        Triple("Alpi", "Alpi", "Le Alpi sono la principale catena montuosa dell'Europa, lunga circa 1.200 km. La vetta più alta è il Monte Bianco."),
        Triple("Sahara", "Sahara", "Il Sahara è il più grande deserto caldo della Terra, con una superficie di circa 9,2 milioni di km², nell'Africa settentrionale."),
        Triple("Vesuvio", "Vesuvio", "Il Vesuvio è un vulcano presso Napoli, alto 1.281 metri. Nel 79 d.C. la sua eruzione distrusse Pompei ed Ercolano."),
        Triple("Vulcano", "Vulcano", "Un vulcano è un'apertura della crosta terrestre da cui fuoriescono magma, gas e ceneri."),
        // history
        Triple("Impero_romano", "Impero romano", "L'Impero romano nacque nel 27 a.C. con Augusto. L'Impero romano d'Occidente cadde nel 476 d.C."),
        Triple("Rivoluzione_francese", "Rivoluzione francese", "La Rivoluzione francese iniziò nel 1789 con la presa della Bastiglia, il 14 luglio, e cambiò l'ordine politico della Francia."),
        Triple("Muro_di_Berlino", "Muro di Berlino", "Il Muro di Berlino fu costruito nel 1961 e cadde il 9 novembre 1989, simbolo della fine della guerra fredda."),
        Triple("Prima_guerra_mondiale", "Prima guerra mondiale", "La prima guerra mondiale si combatté dal 1914 al 1918."),
        Triple("Seconda_guerra_mondiale", "Seconda guerra mondiale", "La seconda guerra mondiale iniziò nel 1939 e finì nel 1945."),
        Triple("Unita_d'Italia", "Unità d'Italia", "Il Regno d'Italia fu proclamato il 17 marzo 1861, completando l'Unità d'Italia."),
        Triple("Cristoforo_Colombo", "Cristoforo Colombo", "Cristoforo Colombo nacque a Genova e nel 1492 raggiunse le isole dell'America per conto della Spagna."),
        Triple("Leonardo_da_Vinci", "Leonardo da Vinci", "Leonardo da Vinci visse dal 1452 al 1519. Fu pittore, ingegnere e scienziato; dipinse la Gioconda."),
        // science
        Triple("Fotosintesi", "Fotosintesi", "La fotosintesi è il processo con cui le piante usano la luce, l'acqua e l'anidride carbonica per produrre glucosio e ossigeno."),
        Triple("Gravita", "Gravità", "La gravità è la forza di attrazione tra le masse. Sulla Terra provoca un'accelerazione di circa 9,81 m/s²."),
        Triple("DNA", "DNA", "Il DNA, acido desossiribonucleico, contiene l'informazione genetica ed ha la forma di una doppia elica."),
        Triple("Sistema_solare", "Sistema solare", "Il Sistema solare è formato dal Sole e dagli oggetti che gli orbitano attorno, tra cui otto pianeti."),
        Triple("Luna", "Luna", "La Luna è l'unico satellite naturale della Terra. La distanza media dalla Terra è di circa 384.400 km."),
        Triple("Acqua", "Acqua", "L'acqua ha formula chimica H2O. A pressione normale bolle a 100 °C. Un litro d'acqua pesa circa un chilogrammo."),
        // tech
        Triple("SSD", "SSD", "Un SSD è un'unità di memoria a stato solido che salva i dati su chip flash, senza parti meccaniche in movimento."),
        Triple("RAM", "RAM", "La RAM è la memoria volatile di un computer: contiene i dati dei programmi in esecuzione e si svuota allo spegnimento."),
        Triple("CPU", "CPU", "La CPU è il processore: esegue le istruzioni dei programmi e coordina i componenti del computer."),
        Triple("Wi-Fi", "Wi-Fi", "Il Wi-Fi è una tecnologia di rete senza fili basata sullo standard IEEE 802.11."),
        Triple("VPN", "VPN", "Una VPN crea un collegamento cifrato tra un dispositivo e un server, nascondendo il traffico a chi sta in mezzo."),
        Triple("Bluetooth", "Bluetooth", "Il Bluetooth è uno standard wireless a corto raggio che usa la banda a 2,4 GHz per collegare dispositivi vicini."),
        Triple("Algoritmo", "Algoritmo", "Un algoritmo è una sequenza finita di passi che risolve un problema."),
        Triple("Crittografia", "Crittografia", "La crittografia rende un messaggio illeggibile a chi non possiede la chiave per decifrarlo."),
        Triple("Firmware", "Firmware", "Il firmware è il software di base di un dispositivo: controlla l'hardware ed è memorizzato in una memoria interna."),
        Triple("Cloud_computing", "Cloud computing", "Il cloud computing è l'uso di server remoti, via rete, per archiviare dati ed eseguire programmi."),
    )

    private val redirects = listOf(
        "Unità_a_stato_solido" to "SSD", "Memoria_ad_accesso_casuale" to "RAM", "Rete_privata_virtuale" to "VPN",
        "Acido_desossiribonucleico" to "DNA", "Mont_Blanc" to "Monte_Bianco", "Calcolatore_centrale" to "CPU",
    )

    /** The current archive (2024). */
    fun current(): ZimKnowledgeSource = source("Mini enciclopedia", "2024-06-18", articles, redirects)

    /**
     * An OLD archive (2012): a different Italy figure (the 2011 census), and an article the new one lacks (Belgio).
     * Used for conflicting / obsolete-source scenarios.
     */
    fun old(): ZimKnowledgeSource = source(
        "Mini enciclopedia (vecchia)", "2012-03-01",
        listOf(
            Triple("Italia", "Italia", "L'Italia è una repubblica dell'Europa meridionale. La capitale è Roma. La popolazione è di circa 59,4 milioni di abitanti (2011)."),
            Triple("Belgio", "Belgio", "Il Belgio è uno Stato dell'Europa occidentale. La capitale è Bruxelles. La popolazione è di circa 11 milioni di abitanti."),
        ),
        emptyList(),
    )

    private fun source(title: String, date: String, arts: List<Triple<String, String, String>>, reds: List<Pair<String, String>>): ZimKnowledgeSource {
        val b = TestZimBuilder().apply { compression = 5; entriesPerCluster = 6; uuidSeed = date.hashCode() and 0x3F }
        arts.forEach { (path, name, lead) -> b.article(path, name, "<html><head><title>$name</title></head><body><h1>$name</h1><p>$lead</p></body></html>") }
        reds.forEach { (path, to) -> b.redirect(path, path.replace('_', ' '), to) }
        b.metadata("Title", title).metadata("Date", date).metadata("Language", "ita")
        return ZimKnowledgeSource(ZimArchive.open(CountingSource(b.build()), TestDecompressor))
    }

    val titles: Set<String> get() = articles.map { it.second }.toSet()
}
