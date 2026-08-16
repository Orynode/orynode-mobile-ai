package ai.orynode.mobile.infrastructure.persistence

import ai.orynode.mobile.domain.EmbeddedKnowledgeChunk
import ai.orynode.mobile.domain.KnowledgeBaseError
import ai.orynode.mobile.domain.KnowledgeChunk
import ai.orynode.mobile.domain.KnowledgeDocument
import ai.orynode.mobile.domain.KnowledgeIndexContract
import ai.orynode.mobile.domain.KnowledgePageSpan
import ai.orynode.mobile.domain.KnowledgeRepository
import ai.orynode.mobile.domain.KnowledgeSearchHit
import ai.orynode.mobile.domain.KnowledgeSearchScope
import ai.orynode.mobile.infrastructure.embedding.ExactCosineSimilarity
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.math.max

/**
 * Persistent hybrid retrieval (SQLite + FTS5 + exact cosine).
 * Schema and fusion formula align with the iOS SQLiteKnowledgeRepository contract.
 */
class SqliteKnowledgeRepository private constructor(
    private val database: SQLiteDatabase,
    private val embeddingDimensions: Int,
    private val ftsEnabled: Boolean,
) : KnowledgeRepository, AutoCloseable {
    private val mutex = Mutex()

    companion object {
        fun open(
            path: Path,
            embeddingDimensions: Int,
            embeddingIndexVersion: String,
            retrievalVersion: String = KnowledgeIndexContract.RETRIEVAL_VERSION,
            chunkerVersion: String = KnowledgeIndexContract.DEFAULT_CHUNKER_VERSION,
            contentHashVersion: String = KnowledgeIndexContract.CONTENT_HASH_VERSION,
        ): SqliteKnowledgeRepository {
            require(embeddingDimensions > 0)
            Files.createDirectories(path.parent)
            val database = SQLiteDatabase.openOrCreateDatabase(path.toFile(), null)
            try {
                database.rawQuery("PRAGMA foreign_keys = ON", null).close()
                database.rawQuery("PRAGMA journal_mode = WAL", null).close()
                database.rawQuery("PRAGMA secure_delete = ON", null).close()
                val ftsEnabled = createSchema(database)
                val hasChunks = scalar(database, "SELECT COUNT(*) FROM chunks") != "0"
                requireCompatibleMetadata(
                    database = database,
                    key = "embedding_index_version",
                    expected = embeddingIndexVersion,
                    hasIndexedData = hasChunks,
                    mismatchMessage = { existing ->
                        "embedding 模型已从 $existing 切换为 $embeddingIndexVersion，必须重建索引"
                    },
                )
                requireCompatibleMetadata(
                    database = database,
                    key = "retrieval_version",
                    expected = retrievalVersion,
                    hasIndexedData = hasChunks,
                    mismatchMessage = { existing ->
                        "检索融合策略已从 $existing 切换为 $retrievalVersion，必须重建索引"
                    },
                )
                requireCompatibleMetadata(
                    database = database,
                    key = "chunker_version",
                    expected = chunkerVersion,
                    hasIndexedData = hasChunks,
                    mismatchMessage = { existing ->
                        "切分策略已从 $existing 切换为 $chunkerVersion，必须重建索引"
                    },
                )
                requireCompatibleMetadata(
                    database = database,
                    key = "content_hash_version",
                    expected = contentHashVersion,
                    hasIndexedData = hasChunks,
                    missingAssumed = KnowledgeIndexContract.LEGACY_CONTENT_HASH_VERSION,
                    mismatchMessage = { existing ->
                        "内容去重哈希已从 $existing 切换为 $contentHashVersion，必须重建索引"
                    },
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS documents_content_hash ON documents(content_hash)",
                )
                return SqliteKnowledgeRepository(database, embeddingDimensions, ftsEnabled)
            } catch (error: Exception) {
                database.close()
                throw error
            }
        }

        private fun createSchema(database: SQLiteDatabase): Boolean {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS documents(
                    id TEXT PRIMARY KEY,
                    source_url TEXT NOT NULL,
                    title TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    state TEXT NOT NULL,
                    imported_chunk_count INTEGER NOT NULL,
                    error_message TEXT,
                    indexed_text TEXT,
                    page_spans_json TEXT,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS chunks(
                    id TEXT PRIMARY KEY,
                    document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                    ordinal INTEGER NOT NULL,
                    heading TEXT,
                    text TEXT NOT NULL,
                    token_estimate INTEGER NOT NULL,
                    embedding BLOB NOT NULL,
                    locator_json TEXT,
                    UNIQUE(document_id, ordinal)
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS index_jobs(
                    document_id TEXT PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE,
                    content_hash TEXT NOT NULL,
                    indexed_text TEXT NOT NULL,
                    page_spans_json TEXT,
                    checkpoint INTEGER NOT NULL,
                    updated_at REAL NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS unpublished_chunks(
                    id TEXT PRIMARY KEY,
                    document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                    ordinal INTEGER NOT NULL,
                    heading TEXT,
                    text TEXT NOT NULL,
                    token_estimate INTEGER NOT NULL,
                    embedding BLOB NOT NULL,
                    locator_json TEXT,
                    UNIQUE(document_id, ordinal)
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS knowledge_metadata(
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """.trimIndent(),
            )
            return try {
                database.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
                        chunk_id UNINDEXED, text, tokenize='unicode61'
                    )
                    """.trimIndent(),
                )
                true
            } catch (_: android.database.sqlite.SQLiteException) {
                // Robolectric / rare OEM builds may lack FTS5. Production API 26+ normally has it.
                // Lexical scoring then falls back to scanning chunks.text.
                false
            }
        }

        private fun requireCompatibleMetadata(
            database: SQLiteDatabase,
            key: String,
            expected: String,
            hasIndexedData: Boolean,
            missingAssumed: String? = null,
            mismatchMessage: (String) -> String,
        ) {
            val stored = scalar(
                database,
                "SELECT value FROM knowledge_metadata WHERE key = ?",
                arrayOf(key),
            )
            val effective = stored ?: if (hasIndexedData) missingAssumed else null
            if (effective != null && effective != expected && hasIndexedData) {
                throw KnowledgeBaseError.IndexVersionMismatch(mismatchMessage(effective))
            }
            database.execSQL(
                """
                INSERT INTO knowledge_metadata(key,value) VALUES(?,?)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value
                """.trimIndent(),
                arrayOf(key, expected),
            )
        }

        private fun scalar(
            database: SQLiteDatabase,
            sql: String,
            args: Array<String>? = null,
        ): String? {
            database.rawQuery(sql, args).use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) return null
                return cursor.getString(0)
            }
        }
    }

    override fun close() {
        database.close()
    }

    override suspend fun document(id: UUID): KnowledgeDocument? = withDb {
        queryDocuments(
            whereSql = "WHERE id = ?",
            args = arrayOf(id.toString()),
            includeIndexedText = true,
        ).firstOrNull()
    }

    override suspend fun document(contentHash: String): KnowledgeDocument? = withDb {
        if (contentHash.isEmpty()) return@withDb null
        queryDocuments(
            whereSql = "WHERE content_hash = ? LIMIT 1",
            args = arrayOf(contentHash),
            includeIndexedText = false,
        ).firstOrNull()
    }

    override suspend fun documents(): List<KnowledgeDocument> = withDb {
        queryDocuments(
            whereSql = "ORDER BY updated_at DESC",
            args = null,
            includeIndexedText = false,
        )
    }

    override suspend fun save(document: KnowledgeDocument) = withDb {
        upsertDocument(document, preserveNilText = true)
    }

    override suspend fun replaceChunks(
        documentId: UUID,
        chunks: List<EmbeddedKnowledgeChunk>,
    ) = withDb {
        database.beginTransaction()
        try {
            deleteLiveChunks(documentId)
            for (value in chunks) {
                insertChunk(value, table = "chunks", writeFts = true)
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    override suspend fun prepareIndexJob(
        documentId: UUID,
        contentHash: String,
        indexedText: String,
        pageSpans: List<KnowledgePageSpan>,
    ): Int = withDb {
        val storedHash = jobContentHash(documentId)
        if (storedHash != contentHash) {
            discardUnpublishedLocked(documentId)
        }
        val checkpoint = if (storedHash == contentHash) {
            unpublishedChunkCountLocked(documentId)
        } else {
            0
        }
        upsertIndexJob(documentId, contentHash, indexedText, pageSpans, checkpoint)
        unpublishedChunkCountLocked(documentId)
    }

    override suspend fun unpublishedChunkCount(documentId: UUID): Int = withDb {
        unpublishedChunkCountLocked(documentId)
    }

    override suspend fun discardUnpublishedChunks(documentId: UUID) = withDb {
        discardUnpublishedLocked(documentId)
    }

    override suspend fun appendUnpublishedChunks(
        documentId: UUID,
        chunks: List<EmbeddedKnowledgeChunk>,
    ) = withDb {
        if (chunks.isEmpty()) return@withDb
        database.beginTransaction()
        try {
            for (value in chunks) {
                if (value.chunk.documentId != documentId) {
                    throw KnowledgeBaseError.Storage("unpublished chunk document mismatch")
                }
                insertChunk(value, table = "unpublished_chunks", writeFts = false)
            }
            updateJobCheckpoint(documentId, unpublishedChunkCountLocked(documentId))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    override suspend fun publishUnpublishedChunks(document: KnowledgeDocument) = withDb {
        database.beginTransaction()
        try {
            deleteLiveChunks(document.id)
            database.execSQL(
                """
                INSERT INTO chunks(id,document_id,ordinal,heading,text,token_estimate,embedding,locator_json)
                SELECT id,document_id,ordinal,heading,text,token_estimate,embedding,locator_json
                FROM unpublished_chunks WHERE document_id = ?
                """.trimIndent(),
                arrayOf(document.id.toString()),
            )
            if (ftsEnabled) {
                database.execSQL(
                    """
                    INSERT INTO chunks_fts(chunk_id,text)
                    SELECT id, text FROM unpublished_chunks WHERE document_id = ?
                    """.trimIndent(),
                    arrayOf(document.id.toString()),
                )
            }
            discardUnpublishedLocked(document.id)
            upsertDocument(document, preserveNilText = false)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    override suspend fun deleteDocument(id: UUID) = withDb {
        database.beginTransaction()
        try {
            if (ftsEnabled) {
                for (chunkId in chunkIds(id)) {
                    database.execSQL("DELETE FROM chunks_fts WHERE chunk_id = ?", arrayOf(chunkId))
                }
            }
            database.execSQL("DELETE FROM documents WHERE id = ?", arrayOf(id.toString()))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    override suspend fun search(
        query: String,
        embedding: FloatArray,
        limit: Int,
        scope: KnowledgeSearchScope,
    ): List<KnowledgeSearchHit> = withDb {
        if (embedding.size != embeddingDimensions) {
            throw KnowledgeBaseError.InvalidEmbeddingDimensions(
                expected = embeddingDimensions,
                actual = embedding.size,
            )
        }
        if (scope is KnowledgeSearchScope.Documents && scope.ids.isEmpty()) {
            return@withDb emptyList()
        }
        val lexical = lexicalScores(query, limit = max(20, limit * 4), scope = scope)
        val lexicalMaximum = lexical.values.maxOrNull() ?: 0f
        val ranked = topScoredChunkIds(
            embedding = embedding,
            lexical = lexical,
            lexicalMaximum = lexicalMaximum,
            limit = max(1, limit),
            scope = scope,
        )
        val loaded = chunksById(ranked.map { it.id })
        ranked.mapNotNull { item ->
            val row = loaded[item.id] ?: return@mapNotNull null
            KnowledgeSearchHit(
                chunk = row.chunk,
                documentTitle = row.title,
                score = item.score,
            )
        }
    }

    override suspend fun chunkCount(): Int = withDb {
        count("SELECT COUNT(*) FROM chunks", null)
    }

    override suspend fun chunkCount(documentId: UUID): Int = withDb {
        count("SELECT COUNT(*) FROM chunks WHERE document_id = ?", arrayOf(documentId.toString()))
    }

    private suspend fun <T> withDb(block: () -> T): T =
        mutex.withLock {
            withContext(Dispatchers.IO) { block() }
        }

    private fun insertChunk(
        value: EmbeddedKnowledgeChunk,
        table: String,
        writeFts: Boolean,
    ) {
        if (table != "chunks" && table != "unpublished_chunks") {
            throw KnowledgeBaseError.Storage("unknown chunk table")
        }
        if (value.embedding.size != embeddingDimensions) {
            throw KnowledgeBaseError.InvalidEmbeddingDimensions(
                expected = embeddingDimensions,
                actual = value.embedding.size,
            )
        }
        val statement = database.compileStatement(
            """
            INSERT INTO $table(id,document_id,ordinal,heading,text,token_estimate,embedding,locator_json)
            VALUES(?,?,?,?,?,?,?,?)
            """.trimIndent(),
        )
        statement.use {
            it.bindString(1, value.chunk.id.toString())
            it.bindString(2, value.chunk.documentId.toString())
            it.bindLong(3, value.chunk.ordinal.toLong())
            bindNullableString(it, 4, value.chunk.heading)
            it.bindString(5, value.chunk.text)
            it.bindLong(6, value.chunk.tokenEstimate.toLong())
            it.bindBlob(7, floatArrayToBytes(value.embedding))
            bindNullableString(it, 8, KnowledgePersistenceCodec.encodeLocator(value.chunk.locator))
            it.executeInsert()
        }
        if (writeFts && ftsEnabled) {
            database.execSQL(
                "INSERT INTO chunks_fts(chunk_id,text) VALUES(?,?)",
                arrayOf(value.chunk.id.toString(), value.chunk.text),
            )
        }
    }

    private fun upsertDocument(document: KnowledgeDocument, preserveNilText: Boolean) {
        val textSql = if (preserveNilText) {
            "indexed_text=COALESCE(excluded.indexed_text, documents.indexed_text), page_spans_json=COALESCE(excluded.page_spans_json, documents.page_spans_json)"
        } else {
            "indexed_text=excluded.indexed_text, page_spans_json=excluded.page_spans_json"
        }
        val statement = database.compileStatement(
            """
            INSERT INTO documents(id,source_url,title,content_hash,state,imported_chunk_count,error_message,indexed_text,page_spans_json,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
            source_url=excluded.source_url,
            title=excluded.title,
            content_hash=excluded.content_hash,
            state=excluded.state,
            imported_chunk_count=excluded.imported_chunk_count,
            error_message=excluded.error_message,
            $textSql,
            updated_at=excluded.updated_at
            """.trimIndent(),
        )
        statement.use {
            it.bindString(1, document.id.toString())
            it.bindString(2, document.sourcePath)
            it.bindString(3, document.title)
            it.bindString(4, document.contentHash)
            it.bindString(5, document.state.toStorage())
            it.bindLong(6, document.importedChunkCount.toLong())
            bindNullableString(it, 7, document.errorMessage)
            bindNullableString(it, 8, document.indexedText)
            bindNullableString(it, 9, KnowledgePersistenceCodec.encodePageSpans(document.pageSpans))
            it.bindDouble(
                10,
                document.createdAt.epochSecond + document.createdAt.nano / 1_000_000_000.0,
            )
            it.bindDouble(
                11,
                document.updatedAt.epochSecond + document.updatedAt.nano / 1_000_000_000.0,
            )
            it.executeInsert()
        }
    }

    private fun deleteLiveChunks(documentId: UUID) {
        if (ftsEnabled) {
            for (chunkId in chunkIds(documentId)) {
                database.execSQL("DELETE FROM chunks_fts WHERE chunk_id = ?", arrayOf(chunkId))
            }
        }
        database.execSQL("DELETE FROM chunks WHERE document_id = ?", arrayOf(documentId.toString()))
    }

    private fun discardUnpublishedLocked(documentId: UUID) {
        database.execSQL(
            "DELETE FROM unpublished_chunks WHERE document_id = ?",
            arrayOf(documentId.toString()),
        )
        database.execSQL(
            "DELETE FROM index_jobs WHERE document_id = ?",
            arrayOf(documentId.toString()),
        )
    }

    private fun jobContentHash(documentId: UUID): String? =
        scalar(
            "SELECT content_hash FROM index_jobs WHERE document_id = ?",
            arrayOf(documentId.toString()),
        )

    private fun upsertIndexJob(
        documentId: UUID,
        contentHash: String,
        indexedText: String,
        pageSpans: List<KnowledgePageSpan>,
        checkpoint: Int,
    ) {
        val now = Instant.now().epochSecond.toDouble()
        val statement = database.compileStatement(
            """
            INSERT INTO index_jobs(document_id,content_hash,indexed_text,page_spans_json,checkpoint,updated_at)
            VALUES(?,?,?,?,?,?)
            ON CONFLICT(document_id) DO UPDATE SET
            content_hash=excluded.content_hash,
            indexed_text=excluded.indexed_text,
            page_spans_json=excluded.page_spans_json,
            checkpoint=excluded.checkpoint,
            updated_at=excluded.updated_at
            """.trimIndent(),
        )
        statement.use {
            it.bindString(1, documentId.toString())
            it.bindString(2, contentHash)
            it.bindString(3, indexedText)
            bindNullableString(it, 4, KnowledgePersistenceCodec.encodePageSpans(pageSpans))
            it.bindLong(5, checkpoint.toLong())
            it.bindDouble(6, now)
            it.executeInsert()
        }
    }

    private fun updateJobCheckpoint(documentId: UUID, checkpoint: Int) {
        val statement = database.compileStatement(
            "UPDATE index_jobs SET checkpoint = ?, updated_at = ? WHERE document_id = ?",
        )
        statement.use {
            it.bindLong(1, checkpoint.toLong())
            it.bindDouble(2, Instant.now().epochSecond.toDouble())
            it.bindString(3, documentId.toString())
            it.executeUpdateDelete()
        }
    }

    private fun unpublishedChunkCountLocked(documentId: UUID): Int =
        count(
            "SELECT COUNT(*) FROM unpublished_chunks WHERE document_id = ?",
            arrayOf(documentId.toString()),
        )

    private fun lexicalScores(
        query: String,
        limit: Int,
        scope: KnowledgeSearchScope,
    ): Map<String, Float> {
        if (!ftsEnabled) {
            return lexicalScoresFallback(query, limit, scope)
        }
        // Include focus terms (e.g. RAG from 什么是RAG). Whole-phrase MATCH alone
        // misses unicode61 tokens like "rag" inside definition sentences.
        val terms = lexicalQueryTerms(query)
            .joinToString(" OR ") { token ->
                "\"${token.replace("\"", "\"\"")}\""
            }
        if (terms.isEmpty()) return emptyMap()

        val ids = scope.documentIds?.sortedBy { it.toString() }
        val scopeSql = if (ids.isNullOrEmpty()) {
            ""
        } else {
            " AND c.document_id IN (${ids.joinToString(",") { "?" }})"
        }
        val sql = """
            SELECT chunks_fts.chunk_id, bm25(chunks_fts)
            FROM chunks_fts
            JOIN chunks c ON c.id=chunks_fts.chunk_id
            JOIN documents d ON d.id=c.document_id
            WHERE chunks_fts MATCH ? AND d.state='ready'$scopeSql
            ORDER BY bm25(chunks_fts) LIMIT ?
        """.trimIndent()
        val args = buildList {
            add(terms)
            ids?.forEach { add(it.toString()) }
            add(limit.toString())
        }.toTypedArray()
        val result = linkedMapOf<String, Float>()
        database.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val rank = cursor.getDouble(1)
                result[id] = max(0.0, -rank).toFloat()
            }
        }
        return result
    }

    /** Used when FTS5 is unavailable (e.g. Robolectric). Same fusion formula still applies. */
    private fun lexicalScoresFallback(
        query: String,
        limit: Int,
        scope: KnowledgeSearchScope,
    ): Map<String, Float> {
        val needles = lexicalQueryTerms(query)
            .map { it.lowercase() }
            .filter { it.length >= 2 }
            .distinct()
        if (needles.isEmpty()) return emptyMap()

        val ids = scope.documentIds?.sortedBy { it.toString() }
        val scopeSql = if (ids.isNullOrEmpty()) {
            ""
        } else {
            " AND c.document_id IN (${ids.joinToString(",") { "?" }})"
        }
        val sql = """
            SELECT c.id, c.text
            FROM chunks c JOIN documents d ON d.id=c.document_id
            WHERE d.state='ready'$scopeSql
        """.trimIndent()
        val args = ids?.map { it.toString() }?.toTypedArray()
        val scored = mutableListOf<Pair<String, Float>>()
        database.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val text = cursor.getString(1).lowercase()
                var score = 0f
                for (needle in needles) {
                    var from = 0
                    var count = 0
                    while (true) {
                        val index = text.indexOf(needle, from)
                        if (index < 0) break
                        count += 1
                        from = index + needle.length
                    }
                    if (count > 0) {
                        score += 1f + kotlin.math.ln(1f + count)
                    }
                }
                if (score > 0f) scored += id to score
            }
        }
        return scored
            .sortedByDescending { it.second }
            .take(limit)
            .toMap()
    }

    /**
     * Lexical MATCH / fallback needles. Must include focus terms extracted from
     * definition-style questions so `"什么是RAG"` still hits a chunk containing `RAG`.
     */
    private fun lexicalQueryTerms(query: String): List<String> {
        val terms = linkedSetOf<String>()
        fun addToken(raw: String) {
            val trimmed = raw.trim()
            if (trimmed.isNotEmpty()) terms += trimmed
            trimmed.split(Regex("[\\s\\p{Punct}]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { terms += it }
        }
        addToken(query)
        val collapsed = query.replace('\u00A0', ' ').trim().replace(Regex("\\s+"), " ")
        val patterns = listOf(
            Regex("^什么是\\s*(.+)$"),
            Regex("^(.+?)\\s*是什么[?？]?$"),
            Regex("^(.+?)\\s*(?:的)?(?:含义|意思|定义)[?？]?$"),
            Regex("^(.+?)\\s*指(?:的是)?什么[?？]?$"),
            Regex("^解释(?:一下)?\\s*(.+)$"),
            Regex("^请问\\s*(.+)$"),
            Regex("^介绍(?:一下)?\\s*(.+)$"),
        )
        for (pattern in patterns) {
            val focus = pattern.matchEntire(collapsed)?.groupValues?.getOrNull(1)?.trim()
            if (!focus.isNullOrEmpty()) addToken(focus)
        }
        return terms.toList()
    }

    private fun topScoredChunkIds(
        embedding: FloatArray,
        lexical: Map<String, Float>,
        lexicalMaximum: Float,
        limit: Int,
        scope: KnowledgeSearchScope,
    ): List<ScoredId> {
        val ids = scope.documentIds?.sortedBy { it.toString() }
        val scopeSql = if (ids.isNullOrEmpty()) {
            ""
        } else {
            " AND c.document_id IN (${ids.joinToString(",") { "?" }})"
        }
        val sql = """
            SELECT c.id, c.embedding
            FROM chunks c JOIN documents d ON d.id=c.document_id
            WHERE d.state='ready'$scopeSql
        """.trimIndent()
        val args = ids?.map { it.toString() }?.toTypedArray()
        val best = ArrayList<ScoredId>(limit)
        database.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                val chunkId = cursor.getString(0)
                val blob = cursor.getBlob(1) ?: continue
                val vector = bytesToFloatArray(blob)
                if (vector.size != embeddingDimensions) continue
                val cosine = ExactCosineSimilarity.score(embedding, vector)
                val fts = if (lexicalMaximum > 0f) {
                    (lexical[chunkId] ?: 0f) / lexicalMaximum
                } else {
                    0f
                }
                // Keep in sync with KnowledgeIndexContract.RETRIEVAL_VERSION.
                insertTopK(best, ScoredId(chunkId, 0.7f * cosine + 0.3f * fts), limit)
            }
        }
        if (best.size < limit) {
            best.sortByDescending { it.score }
        }
        return best
    }

    private fun insertTopK(best: MutableList<ScoredId>, candidate: ScoredId, limit: Int) {
        if (best.size < limit) {
            best += candidate
            if (best.size == limit) {
                best.sortByDescending { it.score }
            }
            return
        }
        if (candidate.score <= best[limit - 1].score) return
        best[limit - 1] = candidate
        best.sortByDescending { it.score }
    }

    private fun chunksById(ids: List<String>): Map<String, ChunkRow> {
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        val sql = """
            SELECT c.id,c.document_id,c.ordinal,c.heading,c.text,c.token_estimate,d.title,c.locator_json
            FROM chunks c JOIN documents d ON d.id=c.document_id
            WHERE c.id IN ($placeholders)
        """.trimIndent()
        val result = linkedMapOf<String, ChunkRow>()
        database.rawQuery(sql, ids.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val chunkId = cursor.getString(0)
                val documentId = UUID.fromString(cursor.getString(1))
                result[chunkId] = ChunkRow(
                    chunk = KnowledgeChunk(
                        id = UUID.fromString(chunkId),
                        documentId = documentId,
                        ordinal = cursor.getInt(2),
                        heading = cursor.getStringOrNull(3),
                        text = cursor.getString(4),
                        tokenEstimate = cursor.getInt(5),
                        locator = KnowledgePersistenceCodec.decodeLocator(cursor.getStringOrNull(7)),
                    ),
                    title = cursor.getString(6),
                )
            }
        }
        return result
    }

    private fun queryDocuments(
        whereSql: String,
        args: Array<String>?,
        includeIndexedText: Boolean,
    ): List<KnowledgeDocument> {
        val columns = if (includeIndexedText) {
            "id,source_url,title,content_hash,state,imported_chunk_count,error_message,created_at,updated_at,indexed_text,page_spans_json"
        } else {
            "id,source_url,title,content_hash,state,imported_chunk_count,error_message,created_at,updated_at"
        }
        val sql = "SELECT $columns FROM documents $whereSql"
        val result = mutableListOf<KnowledgeDocument>()
        database.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                val state = documentStateFromStorage(cursor.getString(4)) ?: continue
                result += KnowledgeDocument(
                    id = UUID.fromString(cursor.getString(0)),
                    sourcePath = cursor.getString(1),
                    title = cursor.getString(2),
                    contentHash = cursor.getString(3),
                    state = state,
                    importedChunkCount = cursor.getInt(5),
                    errorMessage = cursor.getStringOrNull(6),
                    indexedText = if (includeIndexedText) cursor.getStringOrNull(9) else null,
                    pageSpans = if (includeIndexedText) {
                        KnowledgePersistenceCodec.decodePageSpans(cursor.getStringOrNull(10))
                    } else {
                        emptyList()
                    },
                    createdAt = Instant.ofEpochMilli((cursor.getDouble(7) * 1000).toLong()),
                    updatedAt = Instant.ofEpochMilli((cursor.getDouble(8) * 1000).toLong()),
                )
            }
        }
        return result
    }

    private fun chunkIds(documentId: UUID): List<String> {
        val result = mutableListOf<String>()
        database.rawQuery(
            "SELECT id FROM chunks WHERE document_id = ?",
            arrayOf(documentId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += cursor.getString(0)
            }
        }
        return result
    }

    private fun count(sql: String, args: Array<String>?): Int {
        database.rawQuery(sql, args).use { cursor ->
            if (!cursor.moveToFirst()) return 0
            return cursor.getInt(0)
        }
    }

    private fun scalar(sql: String, args: Array<String>?): String? {
        database.rawQuery(sql, args).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null
            return cursor.getString(0)
        }
    }

    private data class ScoredId(val id: String, val score: Float)
    private data class ChunkRow(val chunk: KnowledgeChunk, val title: String)
}

private fun KnowledgeDocument.State.toStorage(): String = when (this) {
    KnowledgeDocument.State.Importing -> "importing"
    KnowledgeDocument.State.Ready -> "ready"
    KnowledgeDocument.State.Failed -> "failed"
}

private fun documentStateFromStorage(raw: String): KnowledgeDocument.State? =
    when (raw) {
        "importing" -> KnowledgeDocument.State.Importing
        "ready" -> KnowledgeDocument.State.Ready
        "failed" -> KnowledgeDocument.State.Failed
        else -> null
    }

private fun Cursor.getStringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)

private fun bindNullableString(statement: android.database.sqlite.SQLiteStatement, index: Int, value: String?) {
    if (value == null) {
        statement.bindNull(index)
    } else {
        statement.bindString(index, value)
    }
}

private fun floatArrayToBytes(values: FloatArray): ByteArray {
    val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    buffer.asFloatBuffer().put(values)
    return buffer.array()
}

private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val values = FloatArray(buffer.remaining())
    buffer.get(values)
    return values
}
