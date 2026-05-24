package com.codeleon.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    public OllamaClient ollamaClient(RestClient.Builder builder, AiProperties props) {
        return new OllamaClient(builder, props);
    }

    @Bean
    public QdrantClient qdrantClient(RestClient.Builder builder, AiProperties props) {
        return new QdrantClient(builder, props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "codeleon.ai", name = "enabled", havingValue = "true")
    public ApplicationRunner aiBootstrap(QdrantClient qdrant, AiProperties props) {
        return args -> {
            AiProperties.Ollama o = props.ollama();
            log.info("AI configured: chat-model={}, agent-model={}, embed-model={}",
                    o.chatModel(), o.agentModel(), o.embedModel());
            if (o.agentModel().equals(o.chatModel()) && isLikelyTooSmallForTools(o.agentModel())) {
                log.warn("Agent mode reuses chat-model '{}' which is too small for reliable tool calling. "
                        + "Set OLLAMA_AGENT_MODEL=qwen2.5-coder:3b (or :7b) and ensure Ollama has the model pulled.",
                        o.chatModel());
            }
            try {
                qdrant.ensureCollection();
            } catch (Exception ex) {
                log.warn("Qdrant bootstrap failed (is the 'ai' compose profile up?): {}", ex.getMessage());
            }
        };
    }

    /**
     * Quick heuristic on the model tag — Qwen2.5-coder needs ~3B params
     * to emit tool-call JSON reliably; anything labelled {@code :0.5b}
     * or {@code :1.5b} will skip calls or emit malformed arguments.
     */
    private static boolean isLikelyTooSmallForTools(String modelTag) {
        if (modelTag == null) return false;
        String lower = modelTag.toLowerCase();
        return lower.contains(":0.5b") || lower.contains(":1.5b") || lower.contains(":1b");
    }
}
