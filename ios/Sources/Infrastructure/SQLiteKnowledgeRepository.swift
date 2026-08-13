import Foundation
import OrynodeDomain
import SQLite3

private final class SQLiteConnection: @unchecked Sendable {
    let pointer: OpaquePointer
    init(_ pointer: OpaquePointer) { self.pointer = pointer }
    deinit { sqlite3_close(pointer) }
}

public actor SQLiteKnowledgeRepository: KnowledgeRepository {
    private let connection: SQLiteConnection
    private var database: OpaquePointer { connection.pointer }
    private let embeddingDimensions: Int

    public init(
        url: URL,
        embeddingDimensions: Int,
        embeddingIndexVersion: String = "legacy",
        retrievalVersion: String = KnowledgeIndexContract.retrievalVersion,
        chunkerVersion: String = KnowledgeIndexContract.defaultChunkerVersion,
        contentHashVersion: String = KnowledgeIndexContract.contentHashVersion
    ) throws {
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        var handle: OpaquePointer?
        guard sqlite3_open_v2(
            url.path,
            &handle,
            SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX,
            nil
        ) == SQLITE_OK, let handle else {
            throw KnowledgeBaseError.storage("cannot open SQLite database")
        }
        connection = SQLiteConnection(handle)
        self.embeddingDimensions = embeddingDimensions
        do {
            try Self.execute(handle, sql: "PRAGMA foreign_keys = ON")
            try Self.execute(handle, sql: "PRAGMA journal_mode = WAL")
            try Self.execute(handle, sql: "PRAGMA secure_delete = ON")
            try Self.execute(handle, sql: """
                CREATE TABLE IF NOT EXISTS documents(
                    id TEXT PRIMARY KEY, source_url TEXT NOT NULL, title TEXT NOT NULL,
                    content_hash TEXT NOT NULL, state TEXT NOT NULL,
                    imported_chunk_count INTEGER NOT NULL, error_message TEXT,
                    indexed_text TEXT,
                    page_spans_json TEXT,
                    created_at REAL NOT NULL, updated_at REAL NOT NULL
                )
                """)
            try Self.execute(handle, sql: """
                CREATE TABLE IF NOT EXISTS chunks(
                    id TEXT PRIMARY KEY, document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                    ordinal INTEGER NOT NULL, heading TEXT, text TEXT NOT NULL,
                    token_estimate INTEGER NOT NULL, embedding BLOB NOT NULL,
                    locator_json TEXT,
                    UNIQUE(document_id, ordinal)
                )
                """)
            try Self.execute(handle, sql: """
                CREATE TABLE IF NOT EXISTS index_jobs(
                    document_id TEXT PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE,
                    content_hash TEXT NOT NULL,
                    indexed_text TEXT NOT NULL,
                    page_spans_json TEXT,
                    checkpoint INTEGER NOT NULL,
                    updated_at REAL NOT NULL
                )
                """)
            try Self.execute(handle, sql: """
                CREATE TABLE IF NOT EXISTS unpublished_chunks(
                    id TEXT PRIMARY KEY, document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                    ordinal INTEGER NOT NULL, heading TEXT, text TEXT NOT NULL,
                    token_estimate INTEGER NOT NULL, embedding BLOB NOT NULL,
                    locator_json TEXT,
                    UNIQUE(document_id, ordinal)
                )
                """)
            try Self.migrateColumns(handle)
            try Self.execute(handle, sql: """
                CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
                    chunk_id UNINDEXED, text, tokenize='unicode61'
                )
                """)
            try Self.execute(handle, sql: """
                CREATE TABLE IF NOT EXISTS knowledge_metadata(
                    key TEXT PRIMARY KEY, value TEXT NOT NULL
                )
                """)
            let hasChunks = try Self.scalar(handle, sql: "SELECT COUNT(*) FROM chunks") != "0"
            try Self.requireCompatibleMetadata(
                handle,
                key: "embedding_index_version",
                expected: embeddingIndexVersion,
                hasIndexedData: hasChunks,
                mismatchMessage: { existing in
                    "embedding 模型已从 \(existing) 切换为 \(embeddingIndexVersion)，必须重建索引"
                }
            )
            try Self.requireCompatibleMetadata(
                handle,
                key: "retrieval_version",
                expected: retrievalVersion,
                hasIndexedData: hasChunks,
                mismatchMessage: { existing in
                    "检索融合策略已从 \(existing) 切换为 \(retrievalVersion)，必须重建索引"
                }
            )
            try Self.requireCompatibleMetadata(
                handle,
                key: "chunker_version",
                expected: chunkerVersion,
                hasIndexedData: hasChunks,
                mismatchMessage: { existing in
                    "切分策略已从 \(existing) 切换为 \(chunkerVersion)，必须重建索引"
                }
            )
            try Self.requireCompatibleMetadata(
                handle,
                key: "content_hash_version",
                expected: contentHashVersion,
                hasIndexedData: hasChunks,
                missingAssumed: KnowledgeIndexContract.legacyContentHashVersion,
                mismatchMessage: { existing in
                    "内容去重哈希已从 \(existing) 切换为 \(contentHashVersion)，必须重建索引"
                }
            )
            try Self.execute(
                handle,
                sql: "CREATE INDEX IF NOT EXISTS documents_content_hash ON documents(content_hash)"
            )
            try Self.applyCompleteFileProtection(to: url)
        } catch {
            throw error
        }
    }

    public func document(id: UUID) throws -> KnowledgeDocument? {
        try queryDocuments(whereSQL: "WHERE id = ?", binding: id.uuidString, includeIndexedText: true).first
    }

    public func document(contentHash: String) throws -> KnowledgeDocument? {
        guard !contentHash.isEmpty else { return nil }
        return try queryDocuments(
            whereSQL: "WHERE content_hash = ? LIMIT 1",
            binding: contentHash,
            includeIndexedText: false
        ).first
    }

    public func documents() throws -> [KnowledgeDocument] {
        try queryDocuments(whereSQL: "ORDER BY updated_at DESC", binding: nil, includeIndexedText: false)
    }

    public func save(document: KnowledgeDocument) throws {
        try upsertDocument(document, preserveNilText: true)
    }

    public func replaceChunks(documentID: UUID, chunks: [EmbeddedKnowledgeChunk]) throws {
        try Self.execute(database, sql: "BEGIN IMMEDIATE")
        do {
            try deleteLiveChunks(documentID: documentID)
            for value in chunks {
                guard value.embedding.count == embeddingDimensions else {
                    throw KnowledgeBaseError.invalidEmbeddingDimensions(
                        expected: embeddingDimensions,
                        actual: value.embedding.count
                    )
                }
                try insert(value, table: "chunks", writeFTS: true)
            }
            try Self.execute(database, sql: "COMMIT")
        } catch {
            try? Self.execute(database, sql: "ROLLBACK")
            throw error
        }
    }

    public func deleteDocument(id: UUID) throws {
        try Self.execute(database, sql: "BEGIN IMMEDIATE")
        do {
            for chunkID in try chunkIDs(documentID: id) {
                try executeBound("DELETE FROM chunks_fts WHERE chunk_id = ?", value: chunkID)
            }
            try executeBound("DELETE FROM documents WHERE id = ?", value: id.uuidString)
            try Self.execute(database, sql: "COMMIT")
        } catch {
            try? Self.execute(database, sql: "ROLLBACK")
            throw error
        }
    }

    public func search(
        query: String,
        embedding: [Float],
        limit: Int,
        scope: KnowledgeSearchScope
    ) throws -> [KnowledgeSearchHit] {
        guard embedding.count == embeddingDimensions else {
            throw KnowledgeBaseError.invalidEmbeddingDimensions(
                expected: embeddingDimensions,
                actual: embedding.count
            )
        }
        if case let .documents(ids) = scope, ids.isEmpty { return [] }
        let lexical = try lexicalScores(
            query: query,
            limit: max(20, limit * 4),
            scope: scope
        )
        let lexicalMaximum = lexical.values.max() ?? 0
        let ranked = try topScoredChunkIDs(
            embedding: embedding,
            lexical: lexical,
            lexicalMaximum: lexicalMaximum,
            limit: max(1, limit),
            scope: scope
        )
        let loaded = try chunksByID(ranked.map(\.id))
        return ranked.compactMap { item in
            guard let row = loaded[item.id] else { return nil }
            return KnowledgeSearchHit(
                chunk: row.chunk,
                documentTitle: row.title,
                score: item.score
            )
        }
    }

    public func chunkCount() throws -> Int {
        try count(sql: "SELECT COUNT(*) FROM chunks", binding: nil)
    }

    public func chunkCount(documentID: UUID) throws -> Int {
        try count(sql: "SELECT COUNT(*) FROM chunks WHERE document_id = ?", binding: documentID.uuidString)
    }

    public func unpublishedChunkCount(documentID: UUID) throws -> Int {
        try count(
            sql: "SELECT COUNT(*) FROM unpublished_chunks WHERE document_id = ?",
            binding: documentID.uuidString
        )
    }

    public func discardUnpublishedChunks(documentID: UUID) throws {
        try discardUnpublished(documentID: documentID)
    }

    public func prepareIndexJob(
        documentID: UUID,
        contentHash: String,
        indexedText: String,
        pageSpans: [KnowledgePageSpan]
    ) throws -> Int {
        let storedHash = try jobContentHash(documentID: documentID)
        if storedHash != contentHash {
            try discardUnpublished(documentID: documentID)
        }
        try upsertIndexJob(
            documentID: documentID,
            contentHash: contentHash,
            indexedText: indexedText,
            pageSpans: pageSpans,
            checkpoint: storedHash == contentHash ? try unpublishedChunkCount(documentID: documentID) : 0
        )
        return try unpublishedChunkCount(documentID: documentID)
    }

    public func appendUnpublishedChunks(documentID: UUID, chunks: [EmbeddedKnowledgeChunk]) throws {
        guard !chunks.isEmpty else { return }
        try Self.execute(database, sql: "BEGIN IMMEDIATE")
        do {
            for value in chunks {
                guard value.chunk.documentID == documentID else {
                    throw KnowledgeBaseError.storage("unpublished chunk document mismatch")
                }
                guard value.embedding.count == embeddingDimensions else {
                    throw KnowledgeBaseError.invalidEmbeddingDimensions(
                        expected: embeddingDimensions,
                        actual: value.embedding.count
                    )
                }
                try insert(value, table: "unpublished_chunks", writeFTS: false)
            }
            let checkpoint = try unpublishedChunkCount(documentID: documentID)
            try updateJobCheckpoint(documentID: documentID, checkpoint: checkpoint)
            try Self.execute(database, sql: "COMMIT")
        } catch {
            try? Self.execute(database, sql: "ROLLBACK")
            throw error
        }
    }

    public func publishUnpublishedChunks(document: KnowledgeDocument) throws {
        let documentID = document.id
        try Self.execute(database, sql: "BEGIN IMMEDIATE")
        do {
            try deleteLiveChunks(documentID: documentID)
            try executeBound("""
                INSERT INTO chunks(id,document_id,ordinal,heading,text,token_estimate,embedding,locator_json)
                SELECT id,document_id,ordinal,heading,text,token_estimate,embedding,locator_json
                FROM unpublished_chunks WHERE document_id = ?
                """, value: documentID.uuidString)
            try executeBound("""
                INSERT INTO chunks_fts(chunk_id,text)
                SELECT id, text FROM unpublished_chunks WHERE document_id = ?
                """, value: documentID.uuidString)
            try discardUnpublished(documentID: documentID)
            try upsertDocument(document, preserveNilText: false)
            try Self.execute(database, sql: "COMMIT")
        } catch {
            try? Self.execute(database, sql: "ROLLBACK")
            throw error
        }
    }

    private func insert(
        _ value: EmbeddedKnowledgeChunk,
        table: String,
        writeFTS: Bool
    ) throws {
        guard table == "chunks" || table == "unpublished_chunks" else {
            throw KnowledgeBaseError.storage("unknown chunk table")
        }
        let statement = try prepare("""
            INSERT INTO \(table)(id,document_id,ordinal,heading,text,token_estimate,embedding,locator_json)
            VALUES(?,?,?,?,?,?,?,?)
            """)
        defer { sqlite3_finalize(statement) }
        bind(value.chunk.id.uuidString, to: 1, in: statement)
        bind(value.chunk.documentID.uuidString, to: 2, in: statement)
        sqlite3_bind_int(statement, 3, Int32(value.chunk.ordinal))
        bind(value.chunk.heading, to: 4, in: statement)
        bind(value.chunk.text, to: 5, in: statement)
        sqlite3_bind_int(statement, 6, Int32(value.chunk.tokenEstimate))
        _ = value.embedding.withUnsafeBytes {
            sqlite3_bind_blob(statement, 7, $0.baseAddress, Int32($0.count), Self.transient)
        }
        bind(try Self.encodeLocator(value.chunk.locator), to: 8, in: statement)
        try stepDone(statement)

        guard writeFTS else { return }
        let fts = try prepare("INSERT INTO chunks_fts(chunk_id,text) VALUES(?,?)")
        defer { sqlite3_finalize(fts) }
        bind(value.chunk.id.uuidString, to: 1, in: fts)
        bind(value.chunk.text, to: 2, in: fts)
        try stepDone(fts)
    }

    private func upsertDocument(_ document: KnowledgeDocument, preserveNilText: Bool) throws {
        let textSQL = preserveNilText
            ? "indexed_text=COALESCE(excluded.indexed_text, documents.indexed_text), page_spans_json=COALESCE(excluded.page_spans_json, documents.page_spans_json)"
            : "indexed_text=excluded.indexed_text, page_spans_json=excluded.page_spans_json"
        let sql = """
            INSERT INTO documents(id,source_url,title,content_hash,state,imported_chunk_count,error_message,indexed_text,page_spans_json,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET source_url=excluded.source_url,title=excluded.title,
            content_hash=excluded.content_hash,state=excluded.state,
            imported_chunk_count=excluded.imported_chunk_count,error_message=excluded.error_message,
            \(textSQL),
            updated_at=excluded.updated_at
            """
        let statement = try prepare(sql)
        defer { sqlite3_finalize(statement) }
        bind(document.id.uuidString, to: 1, in: statement)
        bind(document.sourceURL.absoluteString, to: 2, in: statement)
        bind(document.title, to: 3, in: statement)
        bind(document.contentHash, to: 4, in: statement)
        bind(document.state.rawValue, to: 5, in: statement)
        sqlite3_bind_int(statement, 6, Int32(document.importedChunkCount))
        bind(document.errorMessage, to: 7, in: statement)
        bind(document.indexedText, to: 8, in: statement)
        bind(Self.encodePageSpans(document.pageSpans), to: 9, in: statement)
        sqlite3_bind_double(statement, 10, document.createdAt.timeIntervalSince1970)
        sqlite3_bind_double(statement, 11, document.updatedAt.timeIntervalSince1970)
        try stepDone(statement)
    }

    private func deleteLiveChunks(documentID: UUID) throws {
        for id in try chunkIDs(documentID: documentID) {
            try executeBound("DELETE FROM chunks_fts WHERE chunk_id = ?", value: id)
        }
        try executeBound("DELETE FROM chunks WHERE document_id = ?", value: documentID.uuidString)
    }

    private func discardUnpublished(documentID: UUID) throws {
        try executeBound("DELETE FROM unpublished_chunks WHERE document_id = ?", value: documentID.uuidString)
        try executeBound("DELETE FROM index_jobs WHERE document_id = ?", value: documentID.uuidString)
    }

    private func jobContentHash(documentID: UUID) throws -> String? {
        let statement = try prepare("SELECT content_hash FROM index_jobs WHERE document_id = ?")
        defer { sqlite3_finalize(statement) }
        bind(documentID.uuidString, to: 1, in: statement)
        guard sqlite3_step(statement) == SQLITE_ROW else { return nil }
        return string(statement, 0)
    }

    private func upsertIndexJob(
        documentID: UUID,
        contentHash: String,
        indexedText: String,
        pageSpans: [KnowledgePageSpan],
        checkpoint: Int
    ) throws {
        let statement = try prepare("""
            INSERT INTO index_jobs(document_id,content_hash,indexed_text,page_spans_json,checkpoint,updated_at)
            VALUES(?,?,?,?,?,?)
            ON CONFLICT(document_id) DO UPDATE SET
            content_hash=excluded.content_hash,indexed_text=excluded.indexed_text,
            page_spans_json=excluded.page_spans_json,checkpoint=excluded.checkpoint,
            updated_at=excluded.updated_at
            """)
        defer { sqlite3_finalize(statement) }
        bind(documentID.uuidString, to: 1, in: statement)
        bind(contentHash, to: 2, in: statement)
        bind(indexedText, to: 3, in: statement)
        bind(Self.encodePageSpans(pageSpans), to: 4, in: statement)
        sqlite3_bind_int(statement, 5, Int32(checkpoint))
        sqlite3_bind_double(statement, 6, Date().timeIntervalSince1970)
        try stepDone(statement)
    }

    private func updateJobCheckpoint(documentID: UUID, checkpoint: Int) throws {
        let statement = try prepare("UPDATE index_jobs SET checkpoint = ?, updated_at = ? WHERE document_id = ?")
        defer { sqlite3_finalize(statement) }
        sqlite3_bind_int(statement, 1, Int32(checkpoint))
        sqlite3_bind_double(statement, 2, Date().timeIntervalSince1970)
        bind(documentID.uuidString, to: 3, in: statement)
        try stepDone(statement)
    }

    private func lexicalScores(
        query: String,
        limit: Int,
        scope: KnowledgeSearchScope
    ) throws -> [String: Float] {
        let terms = query.split { $0.isWhitespace || $0.isPunctuation }
            .map { "\"\(String($0).replacingOccurrences(of: "\"", with: "\"\""))\"" }
            .joined(separator: " OR ")
        guard !terms.isEmpty else { return [:] }
        let ids = scope.documentIDs?.sorted { $0.uuidString < $1.uuidString }
        let placeholders = ids.map { Array(repeating: "?", count: $0.count).joined(separator: ",") }
        let scopeSQL = placeholders.map { " AND c.document_id IN (\($0))" } ?? ""
        let statement = try prepare("""
            SELECT chunks_fts.chunk_id, bm25(chunks_fts)
            FROM chunks_fts
            JOIN chunks c ON c.id=chunks_fts.chunk_id
            JOIN documents d ON d.id=c.document_id
            WHERE chunks_fts MATCH ? AND d.state='ready'\(scopeSQL)
            ORDER BY bm25(chunks_fts) LIMIT ?
            """)
        defer { sqlite3_finalize(statement) }
        bind(terms, to: 1, in: statement)
        var binding: Int32 = 2
        for id in ids ?? [] {
            bind(id.uuidString, to: binding, in: statement)
            binding += 1
        }
        sqlite3_bind_int(statement, binding, Int32(limit))
        var result: [String: Float] = [:]
        while sqlite3_step(statement) == SQLITE_ROW {
            let id = string(statement, 0)
            let rank = sqlite3_column_double(statement, 1)
            result[id] = Float(max(0, -rank))
        }
        return result
    }

    private func topScoredChunkIDs(
        embedding: [Float],
        lexical: [String: Float],
        lexicalMaximum: Float,
        limit: Int,
        scope: KnowledgeSearchScope
    ) throws -> [(id: String, score: Float)] {
        let ids = scope.documentIDs?.sorted { $0.uuidString < $1.uuidString }
        let placeholders = ids.map { Array(repeating: "?", count: $0.count).joined(separator: ",") }
        let scopeSQL = placeholders.map { " AND c.document_id IN (\($0))" } ?? ""
        let statement = try prepare("""
            SELECT c.id, c.embedding
            FROM chunks c JOIN documents d ON d.id=c.document_id
            WHERE d.state='ready'\(scopeSQL)
            """)
        defer { sqlite3_finalize(statement) }
        for (offset, id) in (ids ?? []).enumerated() {
            bind(id.uuidString, to: Int32(offset + 1), in: statement)
        }

        var best: [(id: String, score: Float)] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            let chunkID = string(statement, 0)
            let count = Int(sqlite3_column_bytes(statement, 1)) / MemoryLayout<Float>.size
            guard let bytes = sqlite3_column_blob(statement, 1), count == embeddingDimensions else {
                continue
            }
            let vector = Array(UnsafeBufferPointer(
                start: bytes.assumingMemoryBound(to: Float.self),
                count: count
            ))
            let cosine = try ExactCosineSimilarity.score(embedding, vector)
            let fts = lexicalMaximum > 0 ? (lexical[chunkID] ?? 0) / lexicalMaximum : 0
            // Keep in sync with KnowledgeIndexContract.retrievalVersion.
            insertTopK(&best, id: chunkID, score: 0.7 * cosine + 0.3 * fts, limit: limit)
        }
        if best.count < limit {
            best.sort { $0.score > $1.score }
        }
        return best
    }

    private func insertTopK(
        _ best: inout [(id: String, score: Float)],
        id: String,
        score: Float,
        limit: Int
    ) {
        if best.count < limit {
            best.append((id, score))
            if best.count == limit {
                best.sort { $0.score > $1.score }
            }
            return
        }
        guard score > best[limit - 1].score else { return }
        best[limit - 1] = (id, score)
        best.sort { $0.score > $1.score }
    }

    private func chunksByID(
        _ ids: [String]
    ) throws -> [String: (chunk: KnowledgeChunk, title: String)] {
        guard !ids.isEmpty else { return [:] }
        let placeholders = Array(repeating: "?", count: ids.count).joined(separator: ",")
        let statement = try prepare("""
            SELECT c.id,c.document_id,c.ordinal,c.heading,c.text,c.token_estimate,d.title,c.locator_json
            FROM chunks c JOIN documents d ON d.id=c.document_id
            WHERE c.id IN (\(placeholders))
            """)
        defer { sqlite3_finalize(statement) }
        for (offset, id) in ids.enumerated() {
            bind(id, to: Int32(offset + 1), in: statement)
        }
        var result: [String: (KnowledgeChunk, String)] = [:]
        while sqlite3_step(statement) == SQLITE_ROW {
            let chunkID = string(statement, 0)
            guard let id = UUID(uuidString: chunkID),
                  let documentID = UUID(uuidString: string(statement, 1)) else { continue }
            result[chunkID] = (
                KnowledgeChunk(
                    id: id,
                    documentID: documentID,
                    ordinal: Int(sqlite3_column_int(statement, 2)),
                    heading: optionalString(statement, 3),
                    text: string(statement, 4),
                    tokenEstimate: Int(sqlite3_column_int(statement, 5)),
                    locator: Self.decodeLocator(optionalString(statement, 7))
                ),
                string(statement, 6)
            )
        }
        return result
    }

    private func queryDocuments(
        whereSQL: String,
        binding: String?,
        includeIndexedText: Bool
    ) throws -> [KnowledgeDocument] {
        let columns = includeIndexedText
            ? "id,source_url,title,content_hash,state,imported_chunk_count,error_message,created_at,updated_at,indexed_text,page_spans_json"
            : "id,source_url,title,content_hash,state,imported_chunk_count,error_message,created_at,updated_at"
        let statement = try prepare("""
            SELECT \(columns)
            FROM documents \(whereSQL)
            """)
        defer { sqlite3_finalize(statement) }
        if let binding { bind(binding, to: 1, in: statement) }
        var result: [KnowledgeDocument] = []
        while sqlite3_step(statement) == SQLITE_ROW {
            guard let id = UUID(uuidString: string(statement, 0)),
                  let url = URL(string: string(statement, 1)),
                  let state = KnowledgeDocument.State(rawValue: string(statement, 4)) else { continue }
            result.append(KnowledgeDocument(
                id: id,
                sourceURL: url,
                title: string(statement, 2),
                contentHash: string(statement, 3),
                state: state,
                importedChunkCount: Int(sqlite3_column_int(statement, 5)),
                errorMessage: optionalString(statement, 6),
                indexedText: includeIndexedText ? optionalString(statement, 9) : nil,
                pageSpans: includeIndexedText ? Self.decodePageSpans(optionalString(statement, 10)) : [],
                createdAt: Date(timeIntervalSince1970: sqlite3_column_double(statement, 7)),
                updatedAt: Date(timeIntervalSince1970: sqlite3_column_double(statement, 8))
            ))
        }
        return result
    }

    private func count(sql: String, binding: String?) throws -> Int {
        let statement = try prepare(sql)
        defer { sqlite3_finalize(statement) }
        if let binding { bind(binding, to: 1, in: statement) }
        guard sqlite3_step(statement) == SQLITE_ROW else { return 0 }
        return Int(sqlite3_column_int(statement, 0))
    }

    private func chunkIDs(documentID: UUID) throws -> [String] {
        let statement = try prepare("SELECT id FROM chunks WHERE document_id = ?")
        defer { sqlite3_finalize(statement) }
        bind(documentID.uuidString, to: 1, in: statement)
        var ids: [String] = []
        while sqlite3_step(statement) == SQLITE_ROW { ids.append(string(statement, 0)) }
        return ids
    }

    private func executeBound(_ sql: String, value: String) throws {
        let statement = try prepare(sql)
        defer { sqlite3_finalize(statement) }
        bind(value, to: 1, in: statement)
        try stepDone(statement)
    }

    private func prepare(_ sql: String) throws -> OpaquePointer {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK,
              let statement else { throw storageError() }
        return statement
    }

    private func stepDone(_ statement: OpaquePointer) throws {
        guard sqlite3_step(statement) == SQLITE_DONE else { throw storageError() }
    }

    private func bind(_ value: String?, to index: Int32, in statement: OpaquePointer) {
        guard let value else {
            sqlite3_bind_null(statement, index)
            return
        }
        sqlite3_bind_text(statement, index, value, -1, Self.transient)
    }

    private func string(_ statement: OpaquePointer, _ index: Int32) -> String {
        guard let value = sqlite3_column_text(statement, index) else { return "" }
        return String(cString: value)
    }

    private func optionalString(_ statement: OpaquePointer, _ index: Int32) -> String? {
        sqlite3_column_type(statement, index) == SQLITE_NULL ? nil : string(statement, index)
    }

    private func storageError() -> KnowledgeBaseError {
        KnowledgeBaseError.storage(String(cString: sqlite3_errmsg(database)))
    }

    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    private static func execute(_ database: OpaquePointer, sql: String) throws {
        var message: UnsafeMutablePointer<CChar>?
        guard sqlite3_exec(database, sql, nil, nil, &message) == SQLITE_OK else {
            let detail = message.map { String(cString: $0) } ?? "SQLite error"
            sqlite3_free(message)
            throw KnowledgeBaseError.storage(detail)
        }
    }

    private static func scalar(_ database: OpaquePointer, sql: String) throws -> String? {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK,
              let statement else {
            throw KnowledgeBaseError.storage(String(cString: sqlite3_errmsg(database)))
        }
        defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_ROW,
              let value = sqlite3_column_text(statement, 0) else { return nil }
        return String(cString: value)
    }

    /// Missing keys are stamped (grandfather) unless `missingAssumed` makes them a mismatch.
    private static func requireCompatibleMetadata(
        _ database: OpaquePointer,
        key: String,
        expected: String,
        hasIndexedData: Bool,
        missingAssumed: String? = nil,
        mismatchMessage: (String) -> String
    ) throws {
        let stored = try scalar(
            database,
            sql: "SELECT value FROM knowledge_metadata WHERE key='\(sqlLiteral(key))'"
        )
        let effective = stored ?? (hasIndexedData ? missingAssumed : nil)
        if let effective, effective != expected, hasIndexedData {
            throw KnowledgeBaseError.storage(mismatchMessage(effective))
        }
        try execute(
            database,
            sql: """
                INSERT INTO knowledge_metadata(key,value)
                VALUES('\(sqlLiteral(key))','\(sqlLiteral(expected))')
                ON CONFLICT(key) DO UPDATE SET value=excluded.value
                """
        )
    }

    private static func sqlLiteral(_ value: String) -> String {
        value.replacingOccurrences(of: "'", with: "''")
    }

    private static func applyCompleteFileProtection(to databaseURL: URL) throws {
        let fileManager = FileManager.default
        for url in [
            databaseURL,
            URL(fileURLWithPath: databaseURL.path + "-wal"),
            URL(fileURLWithPath: databaseURL.path + "-shm"),
        ] where fileManager.fileExists(atPath: url.path) {
            try fileManager.setAttributes(
                [.protectionKey: FileProtectionType.complete],
                ofItemAtPath: url.path
            )
        }
    }

    private static func migrateColumns(_ database: OpaquePointer) throws {
        let documentColumns = try tableColumns(database, table: "documents")
        if !documentColumns.contains("indexed_text") {
            try execute(database, sql: "ALTER TABLE documents ADD COLUMN indexed_text TEXT")
        }
        if !documentColumns.contains("page_spans_json") {
            try execute(database, sql: "ALTER TABLE documents ADD COLUMN page_spans_json TEXT")
        }
        let chunkColumns = try tableColumns(database, table: "chunks")
        if !chunkColumns.contains("locator_json") {
            try execute(database, sql: "ALTER TABLE chunks ADD COLUMN locator_json TEXT")
        }
    }

    private static func tableColumns(_ database: OpaquePointer, table: String) throws -> Set<String> {
        let statementSQL = "PRAGMA table_info(\(table))"
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, statementSQL, -1, &statement, nil) == SQLITE_OK,
              let statement else {
            throw KnowledgeBaseError.storage(String(cString: sqlite3_errmsg(database)))
        }
        defer { sqlite3_finalize(statement) }
        var columns: Set<String> = []
        while sqlite3_step(statement) == SQLITE_ROW {
            if let name = sqlite3_column_text(statement, 1) {
                columns.insert(String(cString: name))
            }
        }
        return columns
    }

    private static func encodeLocator(_ locator: SourceLocator?) throws -> String? {
        guard let locator else { return nil }
        let data = try JSONEncoder().encode(locator)
        return String(data: data, encoding: .utf8)
    }

    private static func decodeLocator(_ json: String?) -> SourceLocator? {
        guard let json, let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(SourceLocator.self, from: data)
    }

    private static func encodePageSpans(_ spans: [KnowledgePageSpan]) -> String? {
        guard !spans.isEmpty else { return nil }
        let data = try? JSONEncoder().encode(spans)
        return data.flatMap { String(data: $0, encoding: .utf8) }
    }

    private static func decodePageSpans(_ json: String?) -> [KnowledgePageSpan] {
        guard let json, let data = json.data(using: .utf8) else { return [] }
        return (try? JSONDecoder().decode([KnowledgePageSpan].self, from: data)) ?? []
    }
}
