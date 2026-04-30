package com.codeleon.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "codeleon.ai")
public record AiProperties(
        boolean enabled,
        Ollama ollama,
        Qdrant qdrant
) {
    public AiProperties {
        if (ollama == null) ollama = new Ollama(null, null, null, null);
        if (qdrant == null) qdrant = new Qdrant(null, null, 0, null);
    }

    public record Ollama(String url, String chatModel, String embedModel, Duration requestTimeout) {
        public Ollama {
            if (url == null || url.isBlank()) url = "http://localhost:11434";
            if (chatModel == null || chatModel.isBlank()) chatModel = "qwen2.5-coder:0.5b";
            if (embedModel == null || embedModel.isBlank()) embedModel = "nomic-embed-text";
            if (requestTimeout == null) requestTimeout = Duration.ofSeconds(60);
        }
    }

    public record Qdrant(String url, String collection, int vectorSize, String distance) {
        public Qdrant {
            if (url == null || url.isBlank()) url = "http://localhost:6333";
            if (collection == null || collection.isBlank()) collection = "codeleon-room-files";
            if (vectorSize <= 0) vectorSize = 768;
            if (distance == null || distance.isBlank()) distance = "Cosine";
        }
    }
}
