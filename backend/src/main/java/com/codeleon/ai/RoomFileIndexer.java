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

    private final OllamaClient ollama;
    private final QdrantClient qdrant;
    private final CodeChunkerDispatcher chunker;
    /** Optional second index; null in legacy unit tests where BM25 is not
     *  under test. Production wiring always supplies it. */
    private final Bm25Searcher bm25;
    /** Optional file content mirror for agent tools. Null in legacy tests. */
    private final RoomFileSnapshotStore snapshots;

    // @Autowired pins Spring to this constructor; without it the container
    // sees four public constructors, can't pick one, and falls back to the
    // missing no-arg ctor — wiping every controller that depends on us.
    @Autowired
    public RoomFileIndexer(OllamaClient ollama, QdrantClient qdrant,
                            CodeChunkerDispatcher chunker, Bm25Searcher bm25,
                            RoomFileSnapshotStore snapshots) {
        this.ollama = ollama;
        this.qdrant = qdrant;
        this.chunker = chunker;
        this.bm25 = bm25;
        this.snapshots = snapshots;
    }

    /**
     * Backward-compatible constructor for unit tests that pre-date the
     * snapshot store. Drops the agent tools' content mirror.
     */
    public RoomFileIndexer(OllamaClient ollama, QdrantClient qdrant,
                            CodeChunkerDispatcher chunker, Bm25Searcher bm25) {
        this(ollama, qdrant, chunker, bm25, null);
    }

    /**
     * Backward-compatible constructor for unit tests that pre-date BM25.
     * Skips the lexical index — vector retrieval still works on its own.
     */
    public RoomFileIndexer(OllamaClient ollama, QdrantClient qdrant, CodeChunkerDispatcher chunker) {
        this(ollama, qdrant, chunker, null, null);
    }

    /**
     * Backward-compatible constructor for unit tests that pre-date the chunker.
     * Wires a default {@link CodeChunkerDispatcher} — unknown extensions fall
     * through to the text chunker, so behaviour on path="main" matches the
     * pre-AST indexer.
     */
    public RoomFileIndexer(OllamaClient ollama, QdrantClient qdrant) {
        this(ollama, qdrant, CodeChunkerDispatcher.defaultInstance(), null, null);
    }

    public void deleteRoomIndex(UUID roomId) {
        qdrant.ensureCollection();
        qdrant.deleteByFilter(filterForRoom(roomId));
        if (bm25 != null) bm25.deleteRoom(roomId);
        if (snapshots != null) snapshots.deleteRoom(roomId);
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
            return new IndexResult(0, System.currentTimeMillis() - start);
        }

        Language language = chunker.detect(resolvedPath);
        List<QdrantClient.Point> points = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            CodeChunk chunk = chunks.get(i);
            float[] vector = ollama.embedDocument(chunk.text());
            points.add(new QdrantClient.Point(
                    deterministicId(roomId, resolvedPath, i),
                    vector,
                    buildPayload(roomId, resolvedPath, i, chunk, language)
            ));
        }

        qdrant.upsert(points);
        if (bm25 != null) bm25.upsertFile(roomId, resolvedPath, chunks);
        // Mirror the full file text for agent tools (read_file/list_files).
        // The chunker is destructive — symbols are split — so we have to
        // store the raw input rather than reconstructing from chunks.
        if (snapshots != null) snapshots.put(roomId, resolvedPath, text);
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
