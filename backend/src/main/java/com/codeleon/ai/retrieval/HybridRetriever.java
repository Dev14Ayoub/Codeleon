package com.codeleon.ai.retrieval;

import com.codeleon.ai.OllamaClient;
import com.codeleon.ai.QdrantClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Hybrid retrieval: vector search (dense, semantic) + BM25 (sparse,
 * lexical), merged with Reciprocal Rank Fusion and biased toward the
 * file the user currently has open.
 *
 * <p>Why hybrid: dense embeddings catch paraphrases ("how does auth
 * work?" matches a method documented as "validate session"); BM25
 * catches exact identifiers ("refreshTokenWithBackoff") that the
 * embedder smudges into a generic vector. RRF combines them without
 * needing the two scores to be on the same scale — it ranks by position
 * in each list, which is robust to any one signal being miscalibrated.
 */
@Component
@RequiredArgsConstructor
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    /** RRF constant. 60 is the value from the original Cormack et al. paper —
     *  empirically robust across a wide range of retrieval tasks. */
    static final int RRF_K = 60;

    /** Multiplier applied to a chunk's final score when its path matches the
     *  file the user currently has open. Picked low enough to only break ties
     *  in favour of the active file, not to override genuinely better hits. */
    static final double ACTIVE_FILE_BOOST = 1.3d;

    /** How many candidates each back-end returns before fusion. A wider pool
     *  here lets RRF find consensus picks that neither back-end ranked first;
     *  the dispatcher trims to the caller-requested topK afterwards. */
    static final int CANDIDATE_POOL = 20;

    private final OllamaClient ollama;
    private final QdrantClient qdrant;
    private final Bm25Searcher bm25;

    public List<RetrievedChunk> retrieve(UUID roomId, String query, int topK, String activeFilePath) {
        if (query == null || query.isBlank() || topK <= 0) return List.of();

        // Vector and BM25 are independent — fan them out in parallel on the
        // common ForkJoinPool. Embedding the query (1-2 s on CPU Ollama) used
        // to serialise in front of the in-memory BM25 search, doubling the
        // pre-Ollama latency of every chat. Each side isolates its own
        // failure: a vector outage leaves us with BM25 only, and vice versa.
        CompletableFuture<List<QdrantClient.ScoredPoint>> vectorFuture =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        float[] queryVector = ollama.embedQuery(query);
                        Map<String, Object> filter = Map.of("must", List.of(
                                Map.of("key", "roomId", "match", Map.of("value", roomId.toString()))
                        ));
                        return qdrant.search(queryVector, CANDIDATE_POOL, filter);
                    } catch (Exception ex) {
                        log.warn("Vector retrieval failed for room {}: {} — falling back to BM25 only",
                                roomId, ex.getMessage());
                        return List.<QdrantClient.ScoredPoint>of();
                    }
                });

        CompletableFuture<List<Bm25Searcher.Hit>> bm25Future =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return bm25.search(roomId, query, CANDIDATE_POOL);
                    } catch (Exception ex) {
                        log.warn("BM25 retrieval failed for room {}: {} — falling back to vector only",
                                roomId, ex.getMessage());
                        return List.<Bm25Searcher.Hit>of();
                    }
                });

        List<QdrantClient.ScoredPoint> vectorHits = vectorFuture.join();
        List<Bm25Searcher.Hit> bm25Hits = bm25Future.join();

        return fuse(vectorHits, bm25Hits, topK, activeFilePath);
    }

    /**
     * Reciprocal Rank Fusion: score(d) = Σ over ranking r of 1 / (k + rank_r(d)).
     * Documents are identified by (path, chunkIndex) so the same chunk
     * surfaced by both back-ends stacks both signals into a single entry.
     */
    static List<RetrievedChunk> fuse(List<QdrantClient.ScoredPoint> vectorHits,
                                      List<Bm25Searcher.Hit> bm25Hits,
                                      int topK,
                                      String activeFilePath) {
        Map<String, RetrievedChunk> bucket = new LinkedHashMap<>();

        for (int i = 0; i < vectorHits.size(); i++) {
            QdrantClient.ScoredPoint hit = vectorHits.get(i);
            String key = keyOf(hit);
            double rrfContribution = 1.0d / (RRF_K + (i + 1));
            bucket.merge(key,
                    fromVector(hit, rrfContribution, i + 1),
                    (existing, incoming) -> existing.toBuilder()
                            .fromVector(true)
                            .vectorScore(incoming.vectorScore())
                            .finalScore(existing.finalScore() + rrfContribution)
                            .build());
        }

        for (int i = 0; i < bm25Hits.size(); i++) {
            Bm25Searcher.Hit hit = bm25Hits.get(i);
            String key = keyOf(hit);
            double rrfContribution = 1.0d / (RRF_K + (i + 1));
            bucket.merge(key,
                    fromBm25(hit, rrfContribution),
                    (existing, incoming) -> existing.toBuilder()
                            .fromBm25(true)
                            .bm25Score(incoming.bm25Score())
                            .finalScore(existing.finalScore() + rrfContribution)
                            .build());
        }

        // Active-file boost is applied AFTER fusion so it only nudges ties
        // — a chunk in the open file does not jump ahead of a clearly more
        // relevant chunk in another file.
        if (activeFilePath != null && !activeFilePath.isBlank()) {
            bucket.replaceAll((key, chunk) -> chunk.path().equals(activeFilePath)
                    ? chunk.toBuilder().finalScore(chunk.finalScore() * ACTIVE_FILE_BOOST).build()
                    : chunk);
        }

        List<RetrievedChunk> ranked = new ArrayList<>(bucket.values());
        ranked.sort(Comparator.comparingDouble(RetrievedChunk::finalScore).reversed());
        if (ranked.size() > topK) {
            return new ArrayList<>(ranked.subList(0, topK));
        }
        return ranked;
    }

    private static RetrievedChunk fromVector(QdrantClient.ScoredPoint hit, double rrf, int rank) {
        Map<String, Object> payload = hit.payload();
        return new RetrievedChunk(
                stringOr(payload.get("path"), "unknown"),
                stringOrNull(payload.get("symbol")),
                stringOrNull(payload.get("symbolKind")),
                intOrNull(payload.get("startLine")),
                intOrNull(payload.get("endLine")),
                intOr(payload.get("chunkIndex"), 0),
                stringOr(payload.get("text"), ""),
                hit.score(),
                0.0d,
                rrf,
                true,
                false
        );
    }

    private static RetrievedChunk fromBm25(Bm25Searcher.Hit hit, double rrf) {
        return new RetrievedChunk(
                hit.path() != null ? hit.path() : "unknown",
                hit.symbol(),
                hit.symbolKind(),
                hit.startLine(),
                hit.endLine(),
                hit.chunkIndex(),
                hit.text() != null ? hit.text() : "",
                0.0d,
                hit.score(),
                rrf,
                false,
                true
        );
    }

    private static String keyOf(QdrantClient.ScoredPoint hit) {
        return stringOr(hit.payload().get("path"), "unknown") + "#" + intOr(hit.payload().get("chunkIndex"), 0);
    }

    private static String keyOf(Bm25Searcher.Hit hit) {
        return (hit.path() == null ? "unknown" : hit.path()) + "#" + hit.chunkIndex();
    }

    private static String stringOr(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private static String stringOrNull(Object value) {
        if (value == null) return null;
        String s = value.toString();
        return s.isBlank() ? null : s;
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static int intOr(Object value, int fallback) {
        Integer parsed = intOrNull(value);
        return parsed == null ? fallback : parsed;
    }

}
