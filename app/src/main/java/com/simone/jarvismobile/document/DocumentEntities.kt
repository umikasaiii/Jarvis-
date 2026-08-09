package com.simone.jarvismobile.document

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.simone.jarvismobile.core.document.DocumentChunk
import com.simone.jarvismobile.core.document.DocumentRecord
import com.simone.jarvismobile.core.document.DocumentSource
import com.simone.jarvismobile.core.document.DocumentStatus
import kotlinx.coroutines.flow.Flow

/**
 * Room persistence for imported documents. The metadata index ([DocumentEntity])
 * and the retrievable passages ([DocumentChunkEntity]) survive process death, so
 * a document imported once stays queryable in every future conversation. Enums
 * and lists are stored as strings to keep the schema dependency-free.
 */
@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val fileName: String,
    val displayName: String,
    val originalUri: String,
    val vaultPath: String?,
    val mimeType: String,
    val fileSize: Long,
    val checksumSha256: String,
    val importedAt: Long,
    val modifiedAt: Long,
    val parserVersion: Int,
    val indexVersion: Int,
    val status: String,
    val tags: String,
    val source: String,
    val pageCount: Int?,
    val language: String?,
    val title: String?,
    val author: String?,
)

@Entity(
    tableName = "document_chunks",
    indices = [Index("documentId")],
)
data class DocumentChunkEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val text: String,
    val section: String,
    val page: Int?,
    val chunkIndex: Int,
    val tokenEstimate: Int,
    val documentTitle: String,
    val fileName: String,
)

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(doc: DocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChunks(chunks: List<DocumentChunkEntity>)

    @Query("SELECT * FROM documents ORDER BY importedAt DESC")
    fun observeDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents ORDER BY importedAt DESC")
    suspend fun allDocuments(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE checksumSha256 = :checksum LIMIT 1")
    suspend fun findByChecksum(checksum: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): DocumentEntity?

    @Query("SELECT * FROM document_chunks WHERE documentId IN (:documentIds)")
    suspend fun chunksFor(documentIds: List<String>): List<DocumentChunkEntity>

    @Query(
        "SELECT c.* FROM document_chunks c INNER JOIN documents d ON c.documentId = d.id " +
            "WHERE d.status = 'READY'",
    )
    suspend fun readyChunks(): List<DocumentChunkEntity>

    @Query("UPDATE documents SET status = :status, modifiedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: String, now: Long)

    @Query("DELETE FROM document_chunks WHERE documentId = :documentId")
    suspend fun deleteChunks(documentId: String)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: String)
}

// --- mapping between Room rows and the pure-core models ----------------------

fun DocumentEntity.toRecord(): DocumentRecord = DocumentRecord(
    id = id,
    fileName = fileName,
    displayName = displayName,
    originalUri = originalUri,
    vaultPath = vaultPath,
    mimeType = mimeType,
    fileSize = fileSize,
    checksumSha256 = checksumSha256,
    importedAt = importedAt,
    modifiedAt = modifiedAt,
    parserVersion = parserVersion,
    indexVersion = indexVersion,
    status = runCatching { DocumentStatus.valueOf(status) }.getOrDefault(DocumentStatus.FAILED),
    tags = tags.split('\u001F').filter { it.isNotBlank() },
    source = runCatching { DocumentSource.valueOf(source) }.getOrDefault(DocumentSource.CHAT_ATTACHMENT),
    pageCount = pageCount,
    language = language,
    title = title,
    author = author,
)

fun DocumentRecord.toEntity(): DocumentEntity = DocumentEntity(
    id = id,
    fileName = fileName,
    displayName = displayName,
    originalUri = originalUri,
    vaultPath = vaultPath,
    mimeType = mimeType,
    fileSize = fileSize,
    checksumSha256 = checksumSha256,
    importedAt = importedAt,
    modifiedAt = modifiedAt,
    parserVersion = parserVersion,
    indexVersion = indexVersion,
    status = status.name,
    tags = tags.joinToString("\u001F"),
    source = source.name,
    pageCount = pageCount,
    language = language,
    title = title,
    author = author,
)

fun DocumentChunkEntity.toChunk(): DocumentChunk = DocumentChunk(
    id = id,
    documentId = documentId,
    text = text,
    section = section,
    page = page,
    chunkIndex = chunkIndex,
    tokenEstimate = tokenEstimate,
    documentTitle = documentTitle,
    fileName = fileName,
)

fun DocumentChunk.toEntity(): DocumentChunkEntity = DocumentChunkEntity(
    id = id,
    documentId = documentId,
    text = text,
    section = section,
    page = page,
    chunkIndex = chunkIndex,
    tokenEstimate = tokenEstimate,
    documentTitle = documentTitle,
    fileName = fileName,
)
