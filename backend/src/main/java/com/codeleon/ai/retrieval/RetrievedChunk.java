package com.codeleon.ai.retrieval;

/**
 * Unified result of a hybrid retrieval call. Combines what vector search
 * and BM25 each told us about a chunk so the chat layer can render — and
 * the chat layer's tests can assert against — a single shape regardless
 * of which back-end actually surfaced the document.
 *
 * <p>{@code vectorScore} / {@code bm25Score} are populated only when the
 * matching back-end retrieved the chunk; the other side carries 0. The
 * authoritative ranking signal the rest of the system consumes is
 * {@code finalScore} — derived from Reciprocal Rank Fusion and the
 * active-file boost in {@link HybridRetriever}.
 */
public record RetrievedChunk(
        String path,
        String symbol,
        String symbolKind,
        Integer startLine,
        Integer endLine,
        int chunkIndex,
        String text,
        double vectorScore,
        double bm25Score,
        double finalScore,
        boolean fromVector,
        boolean fromBm25
) {
    public boolean hasLineRange() {
        return startLine != null && endLine != null;
    }

    RetrievedChunkBuilder toBuilder() {
        return new RetrievedChunkBuilder(this);
    }
}
