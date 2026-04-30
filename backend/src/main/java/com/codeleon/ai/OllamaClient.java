package com.codeleon.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;

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
                .body(new ChatRequest(config.chatModel(), messages, false))
                .retrieve()
                .body(ChatResponse.class);
        if (response == null || response.message() == null) {
            throw new IllegalStateException("Ollama returned an empty chat response");
        }
        return response.message().content();
    }

    public AiProperties.Ollama config() {
        return config;
    }

    public record ChatMessage(String role, String content) {
        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }

        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }
    }

    record EmbedRequest(String model, String prompt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbedResponse(float[] embedding) {
    }

    record ChatRequest(String model, List<ChatMessage> messages, boolean stream) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(ChatMessage message, boolean done) {
    }
}
