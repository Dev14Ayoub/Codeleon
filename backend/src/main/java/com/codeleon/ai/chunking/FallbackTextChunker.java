package com.codeleon.ai.chunking;

import java.util.ArrayList;
import java.util.List;

/**
 * Sliding-window chunker for files with no recognised language. Walks
 * the source itself rather than delegating to {@code TextChunker.chunk}
 * so the emitted line range stays aligned with where the chunk text
 * actually lives in the file.
 *
 * <p>The earlier implementation looped over {@code TextChunker.chunk}'s
 * returned slices and assumed one slice per window, but {@code TextChunker}
 * silently drops windows that strip down to empty (a long run of
 * whitespace produces no slice). The cursor then desynced from the real
 * window position, so every chunk emitted after a dropped window
 * reported a line range from earlier in the file.
 */
public final class FallbackTextChunker implements CodeChunker {

    /** Window size, in characters. Tuned for the embedding model's input budget. */
    static final int CHUNK_SIZE = 500;

    /** Sliding overlap so a sentence cut by the window edge appears in both halves. */
    static final int CHUNK_OVERLAP = 50;

    @Override
    public List<CodeChunk> chunk(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<CodeChunk> chunks = new ArrayList<>();
        int step = CHUNK_SIZE - CHUNK_OVERLAP;
        int length = text.length();
        for (int start = 0; start < length; start += step) {
            int end = Math.min(start + CHUNK_SIZE, length);
            String slice = text.substring(start, end).strip();
            if (!slice.isEmpty()) {
                int startLine = lineAt(text, start);
                int endLine = lineAt(text, Math.max(start, end - 1));
                chunks.add(CodeChunk.text(slice, startLine, endLine));
            }
            if (end == length) {
                break;
            }
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
