package com.codeleon.ai;

import java.util.ArrayList;
import java.util.List;

public final class TextChunker {

    private TextChunker() {
    }

    public static List<String> chunk(String text, int size, int overlap) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        if (overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("overlap must be in [0, size)");
        }
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        int step = size - overlap;
        int length = text.length();
        for (int start = 0; start < length; start += step) {
            int end = Math.min(start + size, length);
            String slice = text.substring(start, end).strip();
            if (!slice.isEmpty()) {
                chunks.add(slice);
            }
            if (end == length) {
                break;
            }
        }
        return chunks;
    }
}
