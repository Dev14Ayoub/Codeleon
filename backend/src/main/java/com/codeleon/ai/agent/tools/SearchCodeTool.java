package com.codeleon.ai.agent.tools;

import com.codeleon.ai.agent.AgentTool;
import com.codeleon.ai.agent.ToolExecutionException;
import com.codeleon.ai.retrieval.Bm25Searcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BM25 keyword search across the indexed room. Best for finding exact
 * identifiers — function names, variable names, error strings — where
 * the dense embedder smudges the signal. The companion
 * {@code semantic_search} handles natural-language queries.
 */
@Component
@RequiredArgsConstructor
public class SearchCodeTool implements AgentTool {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 10;

    private final Bm25Searcher bm25;

    @Override
    public String name() {
        return "search_code";
    }

    @Override
    public String description() {
        return "Keyword search across the project (BM25). Use for exact identifiers — function names, "
                + "variable names, error message substrings. Returns matching chunks with file path, "
                + "symbol, and line range. For natural-language questions, use semantic_search instead.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "The keyword or phrase to search for."
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

        List<Bm25Searcher.Hit> hits = bm25.search(roomId, query, topK);
        if (hits.isEmpty()) {
            return "No matches for '" + query + "'. Try a different keyword, or call semantic_search "
                    + "for a natural-language query.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(hits.size()).append(" match").append(hits.size() == 1 ? "" : "es")
                .append(" for '").append(query).append("':\n");
        for (int i = 0; i < hits.size(); i++) {
            Bm25Searcher.Hit h = hits.get(i);
            sb.append("\n[").append(i + 1).append("] ");
            if (h.symbol() != null) sb.append(h.symbol()).append(" — ");
            sb.append(h.path());
            if (h.startLine() != null && h.endLine() != null) {
                sb.append(" (L").append(h.startLine()).append("-L").append(h.endLine()).append(")");
            }
            sb.append("  score=").append(String.format("%.2f", h.score())).append('\n');
            String text = h.text() == null ? "" : h.text();
            // Cap each excerpt — the per-tool budget is enforced upstream
            // but we still want each result readable rather than one huge
            // block of text per hit.
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
