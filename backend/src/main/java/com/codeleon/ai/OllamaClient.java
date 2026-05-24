package com.codeleon.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

public class OllamaClient {

    private final RestClient http;
    private final AiProperties.Ollama config;

    public OllamaClient(RestClient.Builder builder, AiProperties props) {
        this.config = props.ollama();
        this.http = builder.baseUrl(config.url()).build();
    }

    public float[] embed(String text) {
        EmbedResponse response = http.post()
                .uri("/api/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmbedRequest(config.embedModel(), text))
                .retrieve()
                .body(EmbedResponse.class);
        if (response == null || response.embedding() == null) {
            throw new IllegalStateException("Ollama returned an empty embedding");
        }
        return response.embedding();
    }

    public String chat(List<ChatMessage> messages) {
        ChatResponse response = http.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChatRequest(config.chatModel(), messages, null, false))
                .retrieve()
                .body(ChatResponse.class);
        if (response == null || response.message() == null) {
            throw new IllegalStateException("Ollama returned an empty chat response");
        }
        return response.message().content();
    }

    /**
     * Non-streaming chat with a tool catalogue. The model decides whether
     * to answer directly (returns a message with {@code tool_calls=null})
     * or call one or more tools (returns {@code tool_calls} populated and
     * {@code content} usually empty).
     *
     * <p>Streaming is intentionally off here — Ollama's streaming protocol
     * splits tool-call JSON across chunks in a way that is painful to parse
     * reliably, and the agent loop only needs the final answer per step to
     * decide what to do next. The final assistant reply, once tools are
     * exhausted, is streamed via {@link OllamaStreamer}.
     */
    public ChatMessage chatWithTools(List<ChatMessage> messages, List<Map<String, Object>> tools) {
        // Tool calling lives on agentModel rather than chatModel so the
        // 0.5b–1.5b chat default can stay light while operators opt the
        // agent up to a 3b/7b variant for reliable tool emission. The
        // fallback in AiProperties.Ollama keeps everything working when
        // OLLAMA_AGENT_MODEL is unset.
        ChatResponse response = http.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChatRequest(config.agentModel(), messages, tools, false))
                .retrieve()
                .body(ChatResponse.class);
        if (response == null || response.message() == null) {
            throw new IllegalStateException("Ollama returned an empty chat response");
        }
        return response.message();
    }

    public AiProperties.Ollama config() {
        return config;
    }

    /**
     * Chat message in the Ollama protocol. {@code toolCalls} is only set on
     * assistant messages the model emits when calling a tool; {@code name}
     * tags the tool that produced a {@code tool} message so the model can
     * correlate the result.
     *
     * <p>Lombok is not used here because the JSON shape must match Ollama's
     * snake_case spec exactly — we lean on Jackson's @JsonInclude to drop
     * nulls so a plain user message serialises as {@code {role,content}}
     * with no stray empty fields tripping older Ollama versions.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatMessage(
            String role,
            String content,
            String name,
            @com.fasterxml.jackson.annotation.JsonProperty("tool_calls") List<ToolCall> toolCalls
    ) {
        public ChatMessage(String role, String content) {
            this(role, content, null, null);
        }

        public static ChatMessage user(String content) {
            return new ChatMessage("user", content, null, null);
        }

        public static ChatMessage system(String content) {
            return new ChatMessage("system", content, null, null);
        }

        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content, null, null);
        }

        /** Tool result back to the model. {@code name} echoes the tool's
         *  function name so the model knows which call this answers. */
        public static ChatMessage tool(String name, String content) {
            return new ChatMessage("tool", content, name, null);
        }

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    /** Ollama's tool-call envelope. The function name is what we route on;
     *  arguments come pre-parsed as a JSON object. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCall(Function function) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Function(String name, Map<String, Object> arguments) {}
    }

    record EmbedRequest(String model, String prompt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbedResponse(float[] embedding) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChatRequest(String model, List<ChatMessage> messages, List<Map<String, Object>> tools, boolean stream) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(ChatMessage message, boolean done) {
    }
}
