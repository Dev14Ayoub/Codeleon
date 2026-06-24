package com.codeleon.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    public OllamaClient ollamaClient(RestClient.Builder builder, AiProperties props) {
        // Read timeout = the configured Ollama request timeout (CPU inference is
        // slow). Without it the non-streaming embed/chat/agent calls have no
        // read timeout, so a hung Ollama would block their threads forever.
        return new OllamaClient(
                withTimeouts(builder, Duration.ofSeconds(10), props.ollama().requestTimeout()), props);
    }

    @Bean
    public QdrantClient qdrantClient(RestClient.Builder builder, AiProperties props) {
        return new QdrantClient(
                withTimeouts(builder, Duration.ofSeconds(5), Duration.ofSeconds(30)), props);
    }

    /** Clones the builder with connect/read timeouts so a stalled upstream
     *  cannot wedge a request thread indefinitely. */
    private static RestClient.Builder withTimeouts(RestClient.Builder builder, Duration connect, Duration read) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connect.toMillis());
        factory.setReadTimeout((int) read.toMillis());
        return builder.clone().requestFactory(factory);
    }

    @Bean
    @ConditionalOnProperty(prefix = "codeleon.ai", name = "enabled", havingValue = "true")
    public ApplicationRunner aiBootstrap(OllamaClient ollama, QdrantClient qdrant, AiProperties props) {
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
            // Pre-warm the chat model in the background so the very first user
            // chat doesn't pay the cold-load cost (1-3 min for a 7B Q4 on CPU)
            // on the critical path. Runs detached so a slow Ollama can't delay
            // backend startup, and silently no-ops if Ollama is unreachable.
            Thread.ofVirtual().name("ollama-prewarm").start(() -> prewarm(ollama, o));
        };
    }

    private static void prewarm(OllamaClient ollama, AiProperties.Ollama o) {
        long t0 = System.currentTimeMillis();
        try {
            ollama.chat(java.util.List.of(OllamaClient.ChatMessage.user("ok")));
            log.info("Chat model '{}' pre-warmed in {} ms", o.chatModel(), System.currentTimeMillis() - t0);
        } catch (Exception ex) {
            log.warn("Chat model pre-warm failed for '{}': {} — the first user chat will pay the cold-load cost",
                    o.chatModel(), ex.getMessage());
            return;
        }
        // Only pre-warm the agent model separately if it differs from the chat
        // model — otherwise the call above already covered it.
        if (!o.agentModel().equals(o.chatModel())) {
            long t1 = System.currentTimeMillis();
            try {
                ollama.chatWithTools(java.util.List.of(OllamaClient.ChatMessage.user("ok")), java.util.List.of());
                log.info("Agent model '{}' pre-warmed in {} ms", o.agentModel(), System.currentTimeMillis() - t1);
            } catch (Exception ex) {
                log.warn("Agent model pre-warm failed for '{}': {}", o.agentModel(), ex.getMessage());
            }
        }
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
