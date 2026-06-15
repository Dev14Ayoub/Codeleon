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
        if (ollama == null) ollama = new Ollama(null, null, null, null, null, 0);
        if (qdrant == null) qdrant = new Qdrant(null, null, 0, null);
    }

    /**
     * Ollama backend configuration.
     *
     * <p>{@code chatModel} drives one-shot RAG turns. {@code agentModel} drives
     * the tool-using loop ({@code mode="agent"}) and is intentionally split
     * because tool calling needs a meaningfully larger model: 0.5b–1.5b
     * variants emit malformed JSON or skip tool calls entirely. The default
     * is empty so the agent transparently falls back to {@code chatModel} —
     * which means a fresh checkout works end-to-end (with degraded agent
     * quality), and power users opt in to a 3b/7b model when their box has
     * the RAM. Recommended: {@code qwen2.5-coder:7b} for reliable tool
     * calling, {@code qwen2.5-coder:3b} as a 2 GB compromise.
     */
    public record Ollama(String url, String chatModel, String agentModel,
                          String embedModel, Duration requestTimeout, int numCtx) {
        public Ollama {
            if (url == null || url.isBlank()) url = "http://localhost:11434";
            if (chatModel == null || chatModel.isBlank()) chatModel = "qwen2.5-coder:0.5b";
            if (agentModel == null || agentModel.isBlank()) agentModel = chatModel;
            if (embedModel == null || embedModel.isBlank()) embedModel = "nomic-embed-text";
            if (requestTimeout == null) requestTimeout = Duration.ofSeconds(60);
            // Context window sent to Ollama as options.num_ctx. Ollama's own
            // default is ~2048 tokens, which silently truncates a RAG prompt
            // (active file + retrieved excerpts). 8192 fits the small CPU
            // models used here; raise it via OLLAMA_NUM_CTX if RAM allows.
            if (numCtx <= 0) numCtx = 8192;
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
