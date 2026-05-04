package com.codeleon.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ChatRequest(
        @NotBlank @Size(max = 4_000) String query,
        Integer topK,
        List<OllamaClient.ChatMessage> history
) {
    public int topKOrDefault() {
        if (topK == null || topK <= 0) return 5;
        return Math.min(topK, 20);
    }

    public List<OllamaClient.ChatMessage> historyOrEmpty() {
        return history == null ? List.of() : history;
    }
}
