package com.simone.jarvismobile.core.knowledge

/**
 * The subject a factual question is about. Used to pick which offline archives to
 * search first (a cybersecurity question should hit the security manuals before
 * general Wikipedia) and to keep the search fast by touching only 2–4 corpora.
 */
enum class KnowledgeCategory {
    MEDICINE,
    CYBERSECURITY,
    NETWORKING,
    LINUX,
    PROGRAMMING,
    SCIENCE,
    HISTORY,
    GEOGRAPHY,
    BIOGRAPHY,
    LANGUAGE,
    MANUAL,
    GENERAL,
}

/**
 * A corpus "kind" an archive can declare (its category tag). The source selector
 * returns these in priority order; the repository maps them to the actual
 * installed archives. Deliberately coarse — an archive is tagged once, not
 * re-classified per query.
 */
enum class CorpusKind {
    MEDICINE,
    CYBERSECURITY,
    NETWORKING,
    LINUX,
    PROGRAMMING,
    MANUAL,
    WIKTIONARY,
    WIKIPEDIA_IT,
    WIKIPEDIA_EN,
    WIKIBOOKS,
    USER,
    OTHER,
}

/**
 * Classifies a factual question into a [KnowledgeCategory] from keyword cues, in
 * Italian and English. Pure and cheap: it runs before any archive is touched so
 * the heavy corpora aren't all searched blindly (spec §6/§12).
 */
object KnowledgeCategorizer {

    fun detect(query: String): KnowledgeCategory {
        val q = normalize(query)
        // Order matters: the most specific technical domains win over general
        // science so "protocollo TCP" routes to networking, not plain science.
        for ((category, cues) in CUES) {
            if (cues.any { it in q }) return category
        }
        return KnowledgeCategory.GENERAL
    }

    internal fun normalize(s: String): String = " " + s.lowercase()
        .replace('à', 'a').replace('è', 'e').replace('é', 'e')
        .replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim() + " "

    // Each cue is matched with surrounding spaces (see normalize) so "ssh" hits
    // " ssh " but not "flssh". Longer, unambiguous domains are listed first.
    private val CUES: List<Pair<KnowledgeCategory, List<String>>> = listOf(
        KnowledgeCategory.CYBERSECURITY to sp(
            "cybersecurity", "sicurezza informatica", "malware", "ransomware", "phishing",
            "vulnerabilita", "vulnerability", "exploit", "penetration", "pentest", "firewall",
            "crittografia", "encryption", "hashing", "owasp", "xss", "csrf", "sql injection",
        ),
        KnowledgeCategory.NETWORKING to sp(
            "vlan", "subnet", "dhcp", "dns", "tcp", "udp", "ip address", "indirizzo ip",
            "router", "switch", "gateway", "protocollo di rete", "networking", "nat", "bgp", "ospf",
        ),
        KnowledgeCategory.LINUX to sp(
            "linux", "ubuntu", "debian", "bash", "systemd", "kernel", "ssh", "cron", "sudo",
            "unix", "terminale linux", "shell script", "grep", "chmod", "apt",
        ),
        KnowledgeCategory.PROGRAMMING to sp(
            "programmazione", "programming", "kotlin", "java", "python", "javascript", "algoritmo",
            "algorithm", "funzione", "compilatore", "compiler", "api", "framework", "git",
        ),
        KnowledgeCategory.MEDICINE to sp(
            "medicina", "malattia", "sintomo", "sintomi", "diagnosi", "farmaco", "terapia",
            "anatomia", "disease", "symptom", "treatment", "patologia", "virus", "batterio",
        ),
        KnowledgeCategory.LANGUAGE to sp(
            "significato", "definizione", "sinonimo", "etimologia", "traduzione di", "meaning of",
            "definition of", "come si scrive", "grammatica",
        ),
        KnowledgeCategory.SCIENCE to sp(
            "fotosintesi", "chimica", "fisica", "biologia", "matematica", "gravita", "energia",
            "atomo", "molecola", "photosynthesis", "physics", "chemistry", "biology", "teorema",
        ),
        KnowledgeCategory.HISTORY to sp(
            "storia", "guerra mondiale", "impero", "medioevo", "rivoluzione", "antica roma",
            "history", "ancient", "battaglia di", "secolo",
        ),
        KnowledgeCategory.GEOGRAPHY to sp(
            "geografia", "capitale di", "capital of", "fiume", "montagna", "continente",
            "quanti abitanti", "population of", "stato di",
        ),
        KnowledgeCategory.BIOGRAPHY to sp(
            "chi era", "chi e", "who was", "who is", "biografia", "nato nel", "vita di",
        ),
        KnowledgeCategory.MANUAL to sp(
            "manuale", "come configurare", "come installare", "procedura", "guida", "how to configure",
            "how to install", "configurare", "installare", "setup",
        ),
    )

    private fun sp(vararg cues: String): List<String> = cues.map { " ${it.trim()} " }
}

/**
 * Given a category and the query language, returns the corpus kinds to search in
 * priority order (spec §7). The repository intersects this with what's actually
 * installed and searches the first 2–4 available corpora.
 */
object KnowledgeSourceSelector {

    fun corpora(category: KnowledgeCategory, italianQuery: Boolean): List<CorpusKind> {
        val wikiFirst = if (italianQuery) {
            listOf(CorpusKind.WIKIPEDIA_IT, CorpusKind.WIKIBOOKS, CorpusKind.WIKIPEDIA_EN)
        } else {
            listOf(CorpusKind.WIKIPEDIA_EN, CorpusKind.WIKIPEDIA_IT)
        }
        // The user's own imported documents are always worth consulting first.
        val head = listOf(CorpusKind.USER)
        val body = when (category) {
            KnowledgeCategory.MEDICINE ->
                listOf(CorpusKind.MEDICINE, CorpusKind.MANUAL)
            KnowledgeCategory.CYBERSECURITY ->
                listOf(CorpusKind.CYBERSECURITY, CorpusKind.MANUAL, CorpusKind.NETWORKING, CorpusKind.LINUX)
            KnowledgeCategory.NETWORKING ->
                listOf(CorpusKind.NETWORKING, CorpusKind.MANUAL, CorpusKind.CYBERSECURITY)
            KnowledgeCategory.LINUX ->
                listOf(CorpusKind.LINUX, CorpusKind.MANUAL, CorpusKind.PROGRAMMING)
            KnowledgeCategory.PROGRAMMING ->
                listOf(CorpusKind.PROGRAMMING, CorpusKind.MANUAL, CorpusKind.LINUX)
            KnowledgeCategory.MANUAL ->
                listOf(CorpusKind.MANUAL, CorpusKind.USER)
            KnowledgeCategory.LANGUAGE ->
                listOf(CorpusKind.WIKTIONARY)
            else -> emptyList()
        }
        return (head + body + wikiFirst).distinct()
    }
}

/**
 * Turns a natural question into a compact search query and expands common
 * acronyms so an archive keyed on "DHCP" is found from "protocollo che assegna
 * automaticamente gli IP" (spec §8). Produces an Italian and an English form so
 * an English-only archive can still answer an Italian question.
 */
object KnowledgeQueryExpander {

    data class Expanded(
        val primary: String,
        val english: String,
        val terms: List<String>,
    )

    fun expand(query: String): Expanded {
        val base = query.trim()
        val lower = KnowledgeCategorizer.normalize(base)
        val extras = LinkedHashSet<String>()
        for ((needle, expansion) in ACRONYMS) {
            if (" $needle " in lower || needle in lower.trim().split(" ")) extras += expansion
        }
        for ((phrase, expansion) in PHRASES) {
            if (phrase in lower) extras += expansion
        }
        val primary = (base + (if (extras.isEmpty()) "" else " " + extras.joinToString(" "))).trim()
        val terms = primary.lowercase().split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.length >= 2 }
        return Expanded(primary = primary, english = primary, terms = terms)
    }

    // Acronym → its expansion, added to the query so lexical/title search hits it.
    private val ACRONYMS: Map<String, String> = mapOf(
        "dhcp" to "DHCP Dynamic Host Configuration Protocol automatic IP assignment",
        "dns" to "DNS Domain Name System",
        "vlan" to "VLAN Virtual LAN",
        "tcp" to "TCP Transmission Control Protocol",
        "udp" to "UDP User Datagram Protocol",
        "ssh" to "SSH Secure Shell",
        "http" to "HTTP HyperText Transfer Protocol",
        "vpn" to "VPN Virtual Private Network",
        "nat" to "NAT Network Address Translation",
        "sql" to "SQL Structured Query Language",
    )

    // A described concept with no acronym in the text → the term to search.
    private val PHRASES: Map<String, String> = mapOf(
        "assegna automaticamente gli ip" to "DHCP Dynamic Host Configuration Protocol",
        "assigns ip addresses automatically" to "DHCP Dynamic Host Configuration Protocol",
        "traduce i nomi di dominio" to "DNS Domain Name System",
        "translates domain names" to "DNS Domain Name System",
    )
}
