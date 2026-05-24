package com.codeleon.ai.chunking;

/**
 * A single indexable slice of a source file.
 *
 * <p>The chunker emits these instead of raw strings so the RAG index can
 * record where each excerpt lives (file, symbol, line range) and so the
 * chat UI can show "AuthService.refreshToken (L87-L112)" rather than just
 * "AuthService.java#chunk3".
 *
 * <p>{@code symbol} and {@code symbolKind} are {@code null} when no symbol
 * could be attributed (e.g. fallback text chunker, top-of-file comments).
 * {@code startLine} and {@code endLine} are 1-indexed, inclusive.
 */
public record CodeChunk(
        String text,
        String symbol,
        SymbolKind symbolKind,
        int startLine,
        int endLine
) {
    public enum SymbolKind {
        CLASS,
        INTERFACE,
        ENUM,
        RECORD,
        METHOD,
        CONSTRUCTOR,
        FUNCTION,
        FIELD,
        BLOCK,
        TEXT
    }

    public static CodeChunk text(String text, int startLine, int endLine) {
        return new CodeChunk(text, null, SymbolKind.TEXT, startLine, endLine);
    }
}
