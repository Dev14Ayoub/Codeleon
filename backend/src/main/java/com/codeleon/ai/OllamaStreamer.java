package com.codeleon.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Streams Ollama's /api/chat NDJSON response using java.net.http.HttpClient.
 *
 * <p>Kept separate from {@link OllamaClient} because the blocking RestClient is awkward for
 * line-by-line streaming. This component depends only on the JDK and Jackson (already on the
 * classpath via spring-boot-starter-web), so it does not pull in spring-webflux.</p>
 */
@Component
public class OllamaStreamer {

    private static final Logger log = LoggerFactory.getLogger(OllamaStreamer.class);

    private final AiProperties.Ollama config;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public OllamaStreamer(AiProperties props, ObjectMapper mapper) {
        this.config = props.ollama();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * POSTs a chat request with {@code stream: true} to Ollama and invokes {@code onToken} for
     * every assistant token as it arrives. Returns the total assistant content (concatenated).
     *
     * @param messages chat history including the final user message
     * @param onToken  consumer invoked synchronously for each token chunk
     * @return the assembled assistant reply
     */
    public String streamChat(List<OllamaClient.ChatMessage> messages, Consumer<String> onToken) {
        Map<String, Object> body = Map.of(
                "model", config.chatModel(),
                "messages", messages,
                "stream", true
        );

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(config.url() + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .timeout(config.requestTimeout())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize Ollama chat request", ex);
        }

        StringBuilder full = new StringBuilder();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Ollama returned HTTP " + response.statusCode());
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    StreamChunk chunk = mapper.readValue(line, StreamChunk.class);
                    if (chunk.message() != null && chunk.message().content() != null) {
                        String token = chunk.message().content();
                        if (!token.isEmpty()) {
                            full.append(token);
                            onToken.accept(token);
                        }
                    }
                    if (chunk.done()) {
                        break;
                    }
                }
            }
        } catch (java.io.IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Ollama stream interrupted: {}", ex.getMessage());
            throw new IllegalStateException("Ollama stream failed: " + ex.getMessage(), ex);
        }
        return full.toString();
    }

    record StreamChunk(OllamaClient.ChatMessage message, boolean done) {
    }
}
