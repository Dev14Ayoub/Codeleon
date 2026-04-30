package com.codeleon.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextChunkerTest {

    @Test
    void emptyAndBlankTextProduceNoChunks() {
        assertThat(TextChunker.chunk(null, 100, 10)).isEmpty();
        assertThat(TextChunker.chunk("", 100, 10)).isEmpty();
        assertThat(TextChunker.chunk("   \n\t  ", 100, 10)).isEmpty();
    }

    @Test
    void shortTextFitsInOneChunk() {
        List<String> chunks = TextChunker.chunk("hello world", 100, 10);
        assertThat(chunks).containsExactly("hello world");
    }

    @Test
    void longTextSplitsWithOverlap() {
        String text = "a".repeat(1200);
        List<String> chunks = TextChunker.chunk(text, 500, 50);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).hasSize(500);
        assertThat(chunks.get(1)).hasSize(500);
        assertThat(chunks.get(2).length()).isLessThanOrEqualTo(500);
    }

    @Test
    void textExactlyChunkSizeProducesSingleChunk() {
        String text = "x".repeat(500);
        List<String> chunks = TextChunker.chunk(text, 500, 50);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).hasSize(500);
    }

    @Test
    void invalidSizeOrOverlapThrows() {
        assertThatThrownBy(() -> TextChunker.chunk("abc", 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TextChunker.chunk("abc", 100, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TextChunker.chunk("abc", 100, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
