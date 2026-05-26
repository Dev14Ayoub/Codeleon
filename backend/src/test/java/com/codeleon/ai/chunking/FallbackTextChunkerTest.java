package com.codeleon.ai.chunking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackTextChunkerTest {

    private final FallbackTextChunker chunker = new FallbackTextChunker();

    @Test
    void emptyAndBlankInputProduceNoChunks() {
        assertThat(chunker.chunk(null)).isEmpty();
        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk("   \n\n  ")).isEmpty();
    }

    @Test
    void shortTextProducesSingleChunk() {
        List<CodeChunk> chunks = chunker.chunk("hello world");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("hello world");
        assertThat(chunks.get(0).symbolKind()).isEqualTo(CodeChunk.SymbolKind.TEXT);
    }

    @Test
    void longTextSplitsIntoMultipleChunks() {
        // 1200 'a's → 3 windows of 500 with 50 overlap.
        List<CodeChunk> chunks = chunker.chunk("a".repeat(1200));
        assertThat(chunks).hasSize(3);
    }

    @Test
    void chunkAfterBlankWindowReportsCorrectLineRange() {
        // 600 newlines = 600 chars of pure whitespace. The first 500-char
        // window strips to "" and is therefore SKIPPED. The next window
        // starts at offset 450 (CHUNK_SIZE - CHUNK_OVERLAP) and overlaps
        // the real content marker further down.
        //
        // Before the cursor-sync fix this chunk's startLine reported back
        // as line 1 because FallbackTextChunker's cursor stayed at 0
        // (incrementing only per emitted slice, not per attempted window).
        StringBuilder src = new StringBuilder();
        for (int i = 0; i < 600; i++) src.append('\n');
        src.append("REAL_CONTENT_LINE\nREAL_CONTENT_LINE_2\n");

        List<CodeChunk> chunks = chunker.chunk(src.toString());
        assertThat(chunks).isNotEmpty();

        CodeChunk realChunk = chunks.stream()
                .filter(c -> c.text().contains("REAL_CONTENT_LINE"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a chunk containing REAL_CONTENT_LINE"));
        // The real content sits past line 600 — startLine must be in
        // that ballpark, not back at line 1.
        assertThat(realChunk.startLine()).isGreaterThan(400);
    }

    @Test
    void everyChunkRespectsMaxChunkChars() {
        String text = "x".repeat(5_000);
        for (CodeChunk c : chunker.chunk(text)) {
            assertThat(c.text().length()).isLessThanOrEqualTo(CodeChunker.MAX_CHUNK_CHARS);
        }
    }
}
