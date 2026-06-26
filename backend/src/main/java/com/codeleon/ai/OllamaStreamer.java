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
                "stream", true,
                // Explicit context window — without this Ollama falls back to a
                // ~2048-token default that silently truncates the RAG prompt.
                "options", Map.of("num_ctx", config.numCtx()),
                // Pin the model in RAM between turns so the second question in
                // a session doesn't pay the cold-load cost again. The Ollama
                // server's OLLAMA_KEEP_ALIVE env is a default; sending the
                // field on each request is unambiguous across versions.
                "keep_alive", "30m"
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
            // Send with a single retry on transient pre-stream failures
            // (Ollama just restarted, brief network blip). Retry is safe
            // because we haven't emitted any token yet. Once we start
            // reading the response body, retries become unsafe (the SSE
            // client may already have seen partial tokens).
            HttpResponse<InputStream> response = sendWithRetry(request);
            if (response.statusCode() / 100 != 2) {
                // Drain + close the body before throwing. Without this, every
                // non-2xx (a missing model returns 404, OOM kill 503, etc.)
                // leaks an HTTP connection — the bug that surfaced when the 7B
                // model was deleted from Ollama. Bonus: we surface Ollama's
                // error message instead of an opaque status code.
                String errBody;
                try (InputStream errStream = response.body()) {
                    errBody = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
                } catch (java.io.IOException drainEx) {
                    errBody = "(body unavailable: " + drainEx.getMessage() + ")";
                }
                throw new IllegalStateException("Ollama returned HTTP " + response.statusCode() + ": " + errBody);
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

    /**
     * Sends the chat request with one retry on a transient failure (IOException
     * or 5xx). Drains the body of a discarded 5xx response so we don't leak
     * connections. No retry mid-stream — that's the caller's contract.
     */
    private HttpResponse<InputStream> sendWithRetry(HttpRequest request)
            throws java.io.IOException, InterruptedException {
        java.io.IOException lastIo = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() / 100 == 5 && attempt == 1) {
                    try (InputStream drain = response.body()) { drain.readAllBytes(); }
                    log.warn("Ollama returned HTTP {} on attempt 1 — retrying", response.statusCode());
                    Thread.sleep(500L);
                    continue;
                }
                return response;
            } catch (java.io.IOException ex) {
                lastIo = ex;
                if (attempt == 1) {
                    log.warn("Ollama send failed on attempt 1: {} — retrying", ex.getMessage());
                    Thread.sleep(500L);
                    continue;
                }
                throw ex;
            }
        }
        throw lastIo;
    }

    record StreamChunk(OllamaClient.ChatMessage message, boolean done) {
    }
}
