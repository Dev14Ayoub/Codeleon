package com.codeleon.ai.eval;

import com.codeleon.ai.chunking.CodeChunk;
import com.codeleon.ai.chunking.CodeChunkerDispatcher;
import com.codeleon.ai.retrieval.Bm25Searcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval quality regression test. Holds the BM25 searcher to a
 * minimum recall and MRR on a small hand-crafted golden dataset — if a
 * chunker change or tokenisation tweak drops the numbers, this test
 * goes red and the regression cannot ship.
 *
 * <p>Vector retrieval is excluded here: it requires a running Qdrant +
 * Ollama, which would turn this from a unit test into an integration
 * test. BM25 is the lexical leg of the hybrid stack; a regression in
 * lexical recall is the most common chunker bug class, so guarding it
 * catches the breakage class we actually see.
 *
 * <p>Thresholds: {@code recall@5 >= 0.80} and {@code MRR >= 0.55} —
 * picked above the current measured numbers with a small margin so a
 * tiny ranking shift is OK, but a real regression (lost a symbol kind,
 * tokeniser change) goes red.
 */
class RetrievalEvalTest {

    private static final int TOP_K = 5;
    private static final double MIN_RECALL_AT_5 = 0.80d;
    private static final double MIN_MRR = 0.55d;

    private static final List<String> CODEBASE_FILES = List.of(
            "AuthService.java",
            "UserRepository.java",
            "PaymentProcessor.java",
            "RateLimiter.java",
            "EmailNotifier.java",
            "FileUploadController.java"
    );

    private Bm25Searcher bm25;
    private CodeChunkerDispatcher chunker;
    private UUID room;

    @BeforeEach
    void setUp() {
        bm25 = new Bm25Searcher();
        chunker = CodeChunkerDispatcher.defaultInstance();
        room = UUID.randomUUID();
        // Index the fake mini-codebase.
        for (String path : CODEBASE_FILES) {
            String text = readResource("/ai-eval/codebase/" + path);
            List<CodeChunk> chunks = chunker.chunk(path, text);
            bm25.upsertFile(room, path, chunks);
        }
    }

    @AfterEach
    void tearDown() {
        bm25.shutdown();
    }

    @Test
    void retrievalQualityClearsThresholds() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode dataset;
        try (InputStream in = getClass().getResourceAsStream("/ai-eval/golden-queries.json")) {
            assertThat(in).as("golden-queries.json on classpath").isNotNull();
            dataset = mapper.readTree(in);
        }

        List<QueryResult> results = new ArrayList<>();
        for (JsonNode entry : dataset) {
            String query = entry.get("query").asText();
            List<String> expected = new ArrayList<>();
            for (JsonNode p : entry.get("expectedPaths")) expected.add(p.asText());
            String kind = entry.has("kind") ? entry.get("kind").asText() : "unknown";

            List<Bm25Searcher.Hit> hits = bm25.search(room, query, TOP_K);
            List<String> rankedPaths = new ArrayList<>();
            for (Bm25Searcher.Hit h : hits) rankedPaths.add(h.path());

            // recall@K counts distinct expected paths surfaced anywhere
            // in the top-K — not the raw number of hits, which would
            // overshoot when BM25 returns several chunks of the same file.
            java.util.Set<String> distinctRankedPaths = new java.util.LinkedHashSet<>(rankedPaths);
            int distinctMatches = 0;
            for (String exp : expected) {
                if (distinctRankedPaths.contains(exp)) distinctMatches++;
            }
            int firstHitRank = -1;
            for (int rank = 0; rank < rankedPaths.size(); rank++) {
                if (expected.contains(rankedPaths.get(rank))) {
                    firstHitRank = rank + 1; // 1-indexed
                    break;
                }
            }
            double recall = expected.isEmpty() ? 1.0d : (double) distinctMatches / expected.size();
            double reciprocalRank = firstHitRank > 0 ? 1.0d / firstHitRank : 0.0d;
            results.add(new QueryResult(query, kind, expected, rankedPaths, recall, reciprocalRank));
        }

        // Print the scoreboard so a developer running the test sees
        // which queries are weak even when the aggregate clears
        // thresholds. Goes to stdout; surefire captures it.
        printScoreboard(results);

        double meanRecall = results.stream().mapToDouble(QueryResult::recall).average().orElse(0);
        double meanMrr = results.stream().mapToDouble(QueryResult::reciprocalRank).average().orElse(0);

        assertThat(meanRecall)
                .as("mean recall@%d across %d queries", TOP_K, results.size())
                .isGreaterThanOrEqualTo(MIN_RECALL_AT_5);
        assertThat(meanMrr)
                .as("mean reciprocal rank across %d queries", results.size())
                .isGreaterThanOrEqualTo(MIN_MRR);
    }

    private static String readResource(String path) {
        try (InputStream in = RetrievalEvalTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read " + path + ": " + ex.getMessage(), ex);
        }
    }

    private static void printScoreboard(List<QueryResult> results) {
        Map<String, List<QueryResult>> byKind = new LinkedHashMap<>();
        for (QueryResult r : results) byKind.computeIfAbsent(r.kind(), k -> new ArrayList<>()).add(r);

        System.out.println("\n=== Retrieval eval scoreboard ===");
        System.out.printf("%-46s  %-18s  %-6s  %-6s%n", "query", "kind", "rec@5", "rr");
        System.out.println("-".repeat(80));
        for (QueryResult r : results) {
            System.out.printf("%-46s  %-18s  %.2f    %.2f%n",
                    truncate(r.query(), 45), r.kind(), r.recall(), r.reciprocalRank());
        }
        System.out.println("-".repeat(80));
        for (Map.Entry<String, List<QueryResult>> e : byKind.entrySet()) {
            double recall = e.getValue().stream().mapToDouble(QueryResult::recall).average().orElse(0);
            double mrr = e.getValue().stream().mapToDouble(QueryResult::reciprocalRank).average().orElse(0);
            System.out.printf("  %-18s  rec=%.2f  mrr=%.2f  (n=%d)%n",
                    e.getKey(), recall, mrr, e.getValue().size());
        }
        double meanR = results.stream().mapToDouble(QueryResult::recall).average().orElse(0);
        double meanM = results.stream().mapToDouble(QueryResult::reciprocalRank).average().orElse(0);
        System.out.printf("OVERALL  rec@%d=%.2f  MRR=%.2f  (n=%d)%n%n",
                TOP_K, meanR, meanM, results.size());
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private record QueryResult(
            String query,
            String kind,
            List<String> expectedPaths,
            List<String> rankedPaths,
            double recall,
            double reciprocalRank
    ) {}
}
