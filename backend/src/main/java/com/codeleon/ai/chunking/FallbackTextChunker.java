package com.codeleon.ai.chunking;

import com.codeleon.ai.TextChunker;

import java.util.ArrayList;
import java.util.List;

/**
 * Sliding-window chunker for files with no recognised language. Wraps the
 * legacy {@link TextChunker} so the behaviour matches the pre-AST indexer
 * for unknown extensions, READMEs, JSON, etc. Line ranges are approximate —
 * computed from the chunk's offset, not the parser — because there is no
 * parser here.
 */
public final class FallbackTextChunker implements CodeChunker {

    /** Window size, in characters. Tuned for the embedding model's input budget. */
    static final int CHUNK_SIZE = 500;

    /** Sliding overlap so a sentence cut by the window edge appears in both halves. */
    static final int CHUNK_OVERLAP = 50;

    @Override
    public List<CodeChunk> chunk(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<String> slices = TextChunker.chunk(text, CHUNK_SIZE, CHUNK_OVERLAP);
        if (slices.isEmpty()) return List.of();

        List<CodeChunk> chunks = new ArrayList<>(slices.size());
        int cursor = 0;
        int step = CHUNK_SIZE - CHUNK_OVERLAP;
        for (String slice : slices) {
            int startOffset = cursor;
            int endOffset = Math.min(startOffset + CHUNK_SIZE, text.length());
            int startLine = lineAt(text, startOffset);
            int endLine = lineAt(text, Math.max(startOffset, endOffset - 1));
            chunks.add(CodeChunk.text(slice, startLine, endLine));
            cursor += step;
        }
        return chunks;
    }

    private static int lineAt(String text, int offset) {
        int line = 1;
        int bound = Math.min(offset, text.length());
        for (int i = 0; i < bound; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }
}
