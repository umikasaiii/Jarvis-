package com.simone.jarvismobile.core.document

sealed interface DocResolution {
    data class Found(val record: DocumentRecord) : DocResolution
    data class Ambiguous(val candidates: List<DocumentRecord>) : DocResolution
    data object NotFound : DocResolution
    data object NoDocuments : DocResolution
}

/** Maps what the user said ("il manuale della moto", "questo PDF") to a real, READY document. */
object DocumentResolver {
    fun resolve(ref: DocRef, documents: List<DocumentRecord>): DocResolution {
        val ready = documents.filter { it.status == DocumentStatus.READY }
        if (ready.isEmpty()) return DocResolution.NoDocuments
        return when (ref) {
            DocRef.Latest -> DocResolution.Found(ready.maxBy { it.importedAt })
            is DocRef.Named -> byName(ref.name, ready)
        }
    }

    private fun byName(name: String, ready: List<DocumentRecord>): DocResolution {
        val wanted = DocumentTerms.of(name).ifEmpty {
            name.lowercase().split(Regex("""[^\p{L}\p{Nd}]+""")).filter { it.length >= 3 }
        }
        if (wanted.isEmpty()) return DocResolution.NotFound
        val matches = ready.filter { r ->
            val hay = DocumentTerms.of(listOfNotNull(r.displayName, r.title, r.fileName).joinToString(" "))
                .ifEmpty { listOf(r.displayName.lowercase()) }
            val hayText = (hay + r.displayName.lowercase() + (r.title ?: "").lowercase() + r.fileName.lowercase())
            wanted.all { w -> hayText.any { it.contains(w) } }
        }
        return when {
            matches.isEmpty() -> DocResolution.NotFound
            matches.size == 1 -> DocResolution.Found(matches.single())
            else -> {
                val exact = matches.filter { it.displayName.equals(name.trim(), ignoreCase = true) }
                if (exact.size == 1) DocResolution.Found(exact.single()) else DocResolution.Ambiguous(matches.sortedBy { it.displayName })
            }
        }
    }
}
