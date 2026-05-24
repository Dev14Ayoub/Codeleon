package com.codeleon.ai.retrieval;

/**
 * Tiny builder for partial updates on a {@link RetrievedChunk}. Records
 * are immutable, and the RRF merge in {@link HybridRetriever#fuse} only
 * wants to flip two or three fields per round; pulling in a code-gen
 * library for that one use case is overkill, so we hand-roll the
 * minimum surface area here.
 */
final class RetrievedChunkBuilder {

    private String path;
    private String symbol;
    private String symbolKind;
    private Integer startLine;
    private Integer endLine;
    private int chunkIndex;
    private String text;
    private double vectorScore;
    private double bm25Score;
    private double finalScore;
    private boolean fromVector;
    private boolean fromBm25;

    RetrievedChunkBuilder(RetrievedChunk source) {
        this.path = source.path();
        this.symbol = source.symbol();
        this.symbolKind = source.symbolKind();
        this.startLine = source.startLine();
        this.endLine = source.endLine();
        this.chunkIndex = source.chunkIndex();
        this.text = source.text();
        this.vectorScore = source.vectorScore();
        this.bm25Score = source.bm25Score();
        this.finalScore = source.finalScore();
        this.fromVector = source.fromVector();
        this.fromBm25 = source.fromBm25();
    }

    RetrievedChunkBuilder vectorScore(double v) { this.vectorScore = v; return this; }
    RetrievedChunkBuilder bm25Score(double v) { this.bm25Score = v; return this; }
    RetrievedChunkBuilder finalScore(double v) { this.finalScore = v; return this; }
    RetrievedChunkBuilder fromVector(boolean v) { this.fromVector = v; return this; }
    RetrievedChunkBuilder fromBm25(boolean v) { this.fromBm25 = v; return this; }

    RetrievedChunk build() {
        return new RetrievedChunk(
                path, symbol, symbolKind, startLine, endLine, chunkIndex, text,
                vectorScore, bm25Score, finalScore, fromVector, fromBm25
        );
    }
}
