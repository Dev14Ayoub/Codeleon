package com.codeleon.ai.retrieval;

import com.codeleon.ai.QdrantClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRetrieverTest {

    @Test
    void fuseUnionsBothBackEndsAndRanksByRrf() {
        // Vector ranks A first, B second. BM25 ranks B first, C second.
        // RRF should put B at the top (appears high in both lists), then
        // A and C tied below.
        List<QdrantClient.ScoredPoint> vector = List.of(
                scoredPoint("A.java", 0, 0.9d),
                scoredPoint("B.java", 0, 0.7d)
        );
        List<Bm25Searcher.Hit> bm25 = List.of(
                bm25Hit("B.java", 0, 5.0d),
                bm25Hit("C.java", 0, 4.0d)
        );

        List<RetrievedChunk> ranked = HybridRetriever.fuse(vector, bm25, 5, null);

        assertThat(ranked).hasSize(3);
        assertThat(ranked.get(0).path()).isEqualTo("B.java");
        assertThat(ranked.get(0).fromVector()).isTrue();
        assertThat(ranked.get(0).fromBm25()).isTrue();
        assertThat(ranked.get(0).finalScore()).isGreaterThan(ranked.get(1).finalScore());
    }

    @Test
    void fuseDegradesGracefullyWhenOneBackEndIsEmpty() {
        // BM25 finds nothing — vector hits still come through unchanged,
        // ranked by their original order in the vector list.
        List<QdrantClient.ScoredPoint> vector = List.of(
                scoredPoint("A.java", 0, 0.9d),
                scoredPoint("B.java", 0, 0.7d)
        );
        List<RetrievedChunk> ranked = HybridRetriever.fuse(vector, List.of(), 5, null);

        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).path()).isEqualTo("A.java");
        assertThat(ranked.get(0).fromVector()).isTrue();
        assertThat(ranked.get(0).fromBm25()).isFalse();
    }

    @Test
    void activeFileBoostNudgesTiesButDoesNotInvertCleanWinners() {
        // Two chunks at the same RRF rank — only the active file should
        // pull ahead. A truly higher-ranked chunk outside the active file
        // must not get overtaken by a boost on a far weaker candidate.
        List<QdrantClient.ScoredPoint> vector = List.of(
                scoredPoint("other.java", 0, 0.9d),
                scoredPoint("active.java", 0, 0.7d)
        );
        List<Bm25Searcher.Hit> bm25 = List.of(
                bm25Hit("active.java", 0, 4.0d),
                bm25Hit("other.java", 0, 3.5d)
        );

        List<RetrievedChunk> withBoost = HybridRetriever.fuse(vector, bm25, 5, "active.java");
        // active.java surfaces in both lists AND gets the boost — it wins.
        assertThat(withBoost.get(0).path()).isEqualTo("active.java");
        assertThat(withBoost.get(0).finalScore())
                .isGreaterThan(withBoost.get(0).finalScore() / HybridRetriever.ACTIVE_FILE_BOOST);
    }

    @Test
    void topKTrimsResults() {
        List<QdrantClient.ScoredPoint> vector = List.of(
                scoredPoint("A.java", 0, 0.9d),
                scoredPoint("B.java", 0, 0.8d),
                scoredPoint("C.java", 0, 0.7d),
                scoredPoint("D.java", 0, 0.6d)
        );
        assertThat(HybridRetriever.fuse(vector, List.of(), 2, null)).hasSize(2);
    }

    @Test
    void emptyInputsProduceEmptyOutput() {
        assertThat(HybridRetriever.fuse(List.of(), List.of(), 5, null)).isEmpty();
        assertThat(HybridRetriever.fuse(List.of(), List.of(), 5, "active.java")).isEmpty();
    }

    @Test
    void fusedChunkExposesProvenanceForChunkSurfacedByBothBackEnds() {
        List<QdrantClient.ScoredPoint> vector = List.of(scoredPoint("X.java", 0, 0.9d));
        List<Bm25Searcher.Hit> bm25 = List.of(bm25Hit("X.java", 0, 3.0d));

        RetrievedChunk only = HybridRetriever.fuse(vector, bm25, 5, null).get(0);
        assertThat(only.fromVector()).isTrue();
        assertThat(only.fromBm25()).isTrue();
        // RRF contribution of rank 1 in each list = 2 / (60 + 1).
        assertThat(only.finalScore()).isEqualTo(2.0d / (HybridRetriever.RRF_K + 1));
    }

    private static QdrantClient.ScoredPoint scoredPoint(String path, int chunkIndex, double score) {
        return new QdrantClient.ScoredPoint(UUID.randomUUID(), score, Map.of(
                "path", path,
                "chunkIndex", chunkIndex,
                "text", "stub body for " + path
        ));
    }

    private static Bm25Searcher.Hit bm25Hit(String path, int chunkIndex, double score) {
        return new Bm25Searcher.Hit(path, null, null, null, null, chunkIndex, "stub body for " + path, score);
    }
}
