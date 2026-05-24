package com.codeleon.ai.agent.tools;

import com.codeleon.ai.OllamaClient;
import com.codeleon.ai.QdrantClient;
import com.codeleon.ai.agent.AgentTool;
import com.codeleon.ai.agent.ToolExecutionException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dense vector search across the indexed room. Best for natural-language
 * questions ("how does authentication work?", "where do we validate
 * tokens?") where the embedder's semantic matching beats keyword overlap.
 * The companion {@code search_code} handles exact identifier lookups.
 */
@Component
@RequiredArgsConstructor
public class SemanticSearchTool implements AgentTool {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 10;

    private final OllamaClient ollama;
    private final QdrantClient qdrant;

    @Override
    public String name() {
        return "semantic_search";
    }

    @Override
    public String description() {
        return "Semantic vector search across the project. Use for natural-language questions "
                + "about behaviour or intent (e.g. 'how does login work', 'where is the rate limiter'). "
                + "For exact identifier matches, use search_code instead.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "Natural-language description of what you are looking for."
                        ),
                        "topK", Map.of(
                                "type", "integer",
                                "description", "How many results to return (default 5, max 10)."
                        )
                ),
                "required", List.of("query")
        );
    }

    @Override
    public String execute(UUID roomId, Map<String, Object> arguments) throws ToolExecutionException {
        Object rawQuery = arguments.get("query");
        if (rawQuery == null || rawQuery.toString().isBlank()) {
            throw new ToolExecutionException("Missing required argument 'query'");
        }
        String query = rawQuery.toString().trim();
        int topK = parseTopK(arguments.get("topK"));

        float[] vector;
        try {
            vector = ollama.embed(query);
        } catch (RuntimeException ex) {
            throw new ToolExecutionException("Embedding failed: " + ex.getMessage(), ex);
        }

        Map<String, Object> filter = Map.of("must", List.of(
                Map.of("key", "roomId", "match", Map.of("value", roomId.toString()))
        ));
        List<QdrantClient.ScoredPoint> hits;
        try {
            hits = qdrant.search(vector, topK, filter);
        } catch (RuntimeException ex) {
            throw new ToolExecutionException("Vector search failed: " + ex.getMessage(), ex);
        }

        if (hits.isEmpty()) {
            return "No semantically-matching excerpts for '" + query + "'. "
                    + "Try search_code for keyword matching, or list_files to see what is indexed.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(hits.size()).append(" semantic match").append(hits.size() == 1 ? "" : "es")
                .append(" for '").append(query).append("':\n");
        for (int i = 0; i < hits.size(); i++) {
            QdrantClient.ScoredPoint h = hits.get(i);
            Map<String, Object> payload = h.payload();
            sb.append("\n[").append(i + 1).append("] ");
            Object symbol = payload.get("symbol");
            if (symbol != null) sb.append(symbol).append(" — ");
            sb.append(payload.getOrDefault("path", "unknown"));
            Object startLine = payload.get("startLine");
            Object endLine = payload.get("endLine");
            if (startLine != null && endLine != null) {
                sb.append(" (L").append(startLine).append("-L").append(endLine).append(")");
            }
            sb.append("  score=").append(String.format("%.2f", h.score())).append('\n');
            String text = String.valueOf(payload.getOrDefault("text", ""));
            if (text.length() > 400) text = text.substring(0, 400) + "...";
            sb.append(text).append('\n');
        }
        return sb.toString();
    }

    private static int parseTopK(Object raw) {
        if (raw == null) return DEFAULT_TOP_K;
        try {
            int parsed = (raw instanceof Number n) ? n.intValue() : Integer.parseInt(raw.toString());
            if (parsed <= 0) return DEFAULT_TOP_K;
            return Math.min(parsed, MAX_TOP_K);
        } catch (NumberFormatException ignored) {
            return DEFAULT_TOP_K;
        }
    }
}
