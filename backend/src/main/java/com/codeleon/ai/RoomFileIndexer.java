package com.codeleon.ai;

import com.codeleon.ai.chunking.CodeChunk;
import com.codeleon.ai.chunking.CodeChunkerDispatcher;
import com.codeleon.ai.chunking.Language;
import com.codeleon.ai.retrieval.Bm25Searcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RoomFileIndexer {

    private static final Logger log = LoggerFactory.getLogger(RoomFileIndexer.class);

    /**
     * Legacy chunk size/overlap — kept package-visible so the
     * {@link com.codeleon.ai.chunking.FallbackTextChunker} and any code that
     * still computes sliding-window stats reads from one source of truth.
     */
    static final int CHUNK_SIZE = 500;
    static final int CHUNK_OVERLAP = 50;

    /**
     * Hard ceiling on chunks embedded per file. Each chunk is one (slow, CPU)
     * embedding call, so a single huge/generated file (lockfile, minified
     * bundle) could otherwise produce hundreds of calls and stall a whole
     * project index. Beyond this we index only the head of the file.
     */
    static final int MAX_CHUNKS_PER_FILE = 120;

    private final OllamaClient ollama;
    private final QdrantClient qdrant;
    private final CodeChunkerDispatcher chunker;
    /** Optional second index; null in legacy unit tests where BM25 is not
     *  under test. Production wiring always supplies it. */
    private final Bm25Searcher bm25;
    /** Optional file content mirror for agent tools. Null in legacy tests. */
    private final RoomFileSnapshotStore snapshots;
    /** Optional persistent baseline of what is indexed (path -> content hash).
     *  Null in legacy unit tests; production wiring always supplies it. */
    private final RoomFileIndexStateRepository indexState;

    // @Autowired pins Spring to this constructor; without it the container
    // sees several public constructors, can't pick one, and falls back to the
    // missing no-arg ctor — wiping every controller that depends on us.
    @Autowired
    public RoomFileIndexer(OllamaClient ollama, QdrantClient qdrant,
                            CodeChunkerDispatcher chunker, Bm25Searcher bm25,
                            RoomFileSnapshotStore snapshots,
                            RoomFileIndexStateRepository indexState) {
        this.ollama = ollama;
        this.qdrant = qdrant;
        this.chunker = chunker;
        this.bm25 = bm25;
        this.snapshots = snapshots;
        this.indexState = indexState;
    }

    /**
     * Backward-compatible constructor for unit tests that pre-date the
     * persistent index-state store. Drops the durable baseline.
     */
    public RoomFileIndexer(OllamaClient ollama, QdrantClient qdrant,
                            CodeChunkerDispatcher chunker, Bm25Searcher bm25,
                            RoomFileSnapshotStore snapshots) {
        this(ollama, qdrant, chunker, bm25, snapshots, null);
    }

    /**
     * Backward-compatible constructor for unit tests that pre-date the
     * snapshot store. Drops the agent tools' content mirror.
     */
    public RoomFileIndexer(OllamaClient ollama, QdrantClient qdrant,
                            CodeChunkerDispatcher chunker, Bm25Searcher bm25) {
        this(ollama, qdrant, chunker, bm25, null, null);
    }

    /**
     * Backward-compatible constructor for unit tests that pre-date BM25.
     * Skips the lexical index — vector retrieval still works on its own.
     */
    public RoomFileIndexer(OllamaClient ollama, QdrantClient qdrant, CodeChunkerDispatcher chunker) {
        this(ollama, qdrant, chunker, null, null, null);
    }

    /**
     * Backward-compatible constructor for unit tests that pre-date the chunker.
     * Wires a default {@link CodeChunkerDispatcher} — unknown extensions fall
     * through to the text chunker, so behaviour on path="main" matches the
     * pre-AST indexer.
     */
    public RoomFileIndexer(OllamaClient ollama, QdrantClient qdrant) {
        this(ollama, qdrant, CodeChunkerDispatcher.defaultInstance(), null, null, null);
    }

    public void deleteRoomIndex(UUID roomId) {
        qdrant.ensureCollection();
        qdrant.deleteByFilter(filterForRoom(roomId));
        if (bm25 != null) bm25.deleteRoom(roomId);
        if (snapshots != null) snapshots.deleteRoom(roomId);
        if (indexState != null) indexState.deleteByRoomId(roomId);
    }

    /**
     * Best-effort variant of {@link #deleteRoomIndex} for the room-deletion
     * paths. Deleting a room from the primary database must never be blocked
     * by an unrelated Qdrant/BM25 outage, so any failure here is logged and
     * swallowed rather than rolling back the caller's transaction. The worst
     * case is a few orphan vectors for a room id that is never reused.
     */
    public void deleteRoomIndexQuietly(UUID roomId) {
        try {
            deleteRoomIndex(roomId);
        } catch (RuntimeException ex) {
            log.warn("Failed to purge AI index for deleted room {}: {}", roomId, ex.getMessage());
        }
    }

    /**
     * Best-effort removal of a single file's index entries (vectors, BM25,
     * snapshot, durable baseline). Used by the room-file delete/rename paths
     * so a removed file's chunks cannot linger even when no client gets the
     * chance to re-index (e.g. the browser is closed right after the delete).
     */
    public void deletePathQuietly(UUID roomId, String path) {
        if (path == null || path.isBlank()) return;
        try {
            // Empty text routes through index()'s "no chunks" branch, which
            // deletes the path's vectors, BM25 docs, snapshot and state row.
            index(roomId, path, "");
        } catch (RuntimeException ex) {
            log.warn("Failed to purge index for path '{}' in room {}: {}", path, roomId, ex.getMessage());
        }
    }

    /**
     * Durable index baseline for a room: {@code path -> content hash}. Lets a
     * client diff the project's current content against what is already
     * embedded and re-index only the files that changed. Empty when nothing
     * is indexed or the state store is absent (legacy unit tests).
     */
    public Map<String, String> indexState(UUID roomId) {
        if (indexState == null) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (RoomFileIndexState row : indexState.findByRoomId(roomId)) {
            out.put(row.getPath(), row.getContentHash());
        }
        return out;
    }

    private void persistState(UUID roomId, String path, String text) {
        try {
            String hash = sha256(text);
            RoomFileIndexState row = indexState.findByRoomIdAndPath(roomId, path).orElse(null);
            if (row == null) {
                row = RoomFileIndexState.builder()
                        .roomId(roomId)
                        .path(path)
                        .contentHash(hash)
                        .indexedAt(Instant.now())
                        .build();
            } else {
                row.setContentHash(hash);
                row.setIndexedAt(Instant.now());
            }
            indexState.save(row);
        } catch (RuntimeException ex) {
            log.warn("Failed to persist index state for path '{}' in room {}: {}", path, roomId, ex.getMessage());
        }
    }

    /** Lowercase hex SHA-256 of the UTF-8 bytes of {@code text}. Must match the
     *  digest the frontend computes (crypto.subtle SHA-256) so the two views
     *  of "is this file already indexed" agree. */
    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    public IndexResult index(UUID roomId, String path, String text) {
        long start = System.currentTimeMillis();
        String resolvedPath = (path == null || path.isBlank()) ? "main" : path;

        qdrant.ensureCollection();
        qdrant.deleteByFilter(filterFor(roomId, resolvedPath));

        List<CodeChunk> chunks = chunker.chunk(resolvedPath, text);
        if (chunks.isEmpty()) {
            log.debug("Skipping index for room {} path {}: no chunks", roomId, resolvedPath);
            // Still clear any stale BM25 docs for this path so a file going
            // from "had content" to "empty" doesn't leave ghosts in the index.
            if (bm25 != null) bm25.deletePath(roomId, resolvedPath);
            if (snapshots != null) snapshots.deletePath(roomId, resolvedPath);
            if (indexState != null) indexState.deleteByRoomIdAndPath(roomId, resolvedPath);
            return new IndexResult(0, System.currentTimeMillis() - start);
        }

        if (chunks.size() > MAX_CHUNKS_PER_FILE) {
            log.warn("File {} in room {} produced {} chunks; capping at {} to bound embedding work",
                    resolvedPath, roomId, chunks.size(), MAX_CHUNKS_PER_FILE);
            chunks = new ArrayList<>(chunks.subList(0, MAX_CHUNKS_PER_FILE));
        }

        Language language = chunker.detect(resolvedPath);
        List<QdrantClient.Point> points = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            CodeChunk chunk = chunks.get(i);
            // Per-chunk isolation: a chunk that overflows the embedder's token
            // window (minified or non-Latin content) must not drop the whole
            // file — skip just that chunk's vector. BM25 still indexes it below.
            try {
                float[] vector = ollama.embedDocument(chunk.text());
                points.add(new QdrantClient.Point(
                        deterministicId(roomId, resolvedPath, i),
                        vector,
                        buildPayload(roomId, resolvedPath, i, chunk, language)
                ));
            } catch (RuntimeException ex) {
                log.warn("Skipping chunk {} of '{}' (room {}): embedding failed: {}",
                        i, resolvedPath, roomId, ex.getMessage());
            }
        }

        if (!points.isEmpty()) {
            qdrant.upsert(points);
        }
        if (bm25 != null) bm25.upsertFile(roomId, resolvedPath, chunks);
        // Mirror the full file text for agent tools (read_file/list_files).
        // The chunker is destructive — symbols are split — so we have to
        // store the raw input rather than reconstructing from chunks.
        if (snapshots != null) snapshots.put(roomId, resolvedPath, text);
        // Record the durable baseline so clients can skip re-embedding this
        // exact text on a later mount/refresh. Best-effort: a state write
        // failure must not fail an otherwise successful index.
        if (indexState != null) persistState(roomId, resolvedPath, text);
        long duration = System.currentTimeMillis() - start;
        log.info("Indexed {} chunks for room {} path {} ({}) in {} ms",
                chunks.size(), roomId, resolvedPath, language, duration);
        return new IndexResult(chunks.size(), duration);
    }

    /**
     * Payload shape preserved for backward compatibility — {@code roomId},
     * {@code path}, {@code chunkIndex}, {@code text} keep their original
     * keys. The new semantic fields ({@code symbol}, {@code symbolKind},
     * {@code startLine}, {@code endLine}, {@code language}) are appended;
     * legacy consumers that read only the original keys are unaffected.
     */
    private static Map<String, Object> buildPayload(UUID roomId, String path, int chunkIndex,
                                                     CodeChunk chunk, Language language) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("roomId", roomId.toString());
        payload.put("path", path);
        payload.put("chunkIndex", chunkIndex);
        payload.put("text", chunk.text());
        if (chunk.symbol() != null) {
            payload.put("symbol", chunk.symbol());
        }
        if (chunk.symbolKind() != null) {
            payload.put("symbolKind", chunk.symbolKind().name());
        }
        payload.put("startLine", chunk.startLine());
        payload.put("endLine", chunk.endLine());
        payload.put("language", language.name());
        return payload;
    }

    static UUID deterministicId(UUID roomId, String path, int chunkIndex) {
        String name = roomId + ":" + path + ":" + chunkIndex;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, Object> filterFor(UUID roomId, String path) {
        return Map.of("must", List.of(
                Map.of("key", "roomId", "match", Map.of("value", roomId.toString())),
                Map.of("key", "path", "match", Map.of("value", path))
        ));
    }

    static Map<String, Object> filterForRoom(UUID roomId) {
        return Map.of("must", List.of(
                Map.of("key", "roomId", "match", Map.of("value", roomId.toString()))
        ));
    }
}
