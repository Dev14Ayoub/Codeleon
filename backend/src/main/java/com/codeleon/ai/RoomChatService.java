package com.codeleon.ai;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates RAG chat: embeds the query, retrieves top-K chunks for the room, builds a
 * grounded prompt, and streams Ollama's response into the provided {@link SseEmitter}.
 */
@Service
@RequiredArgsConstructor
public class RoomChatService {

    private static final Logger log = LoggerFactory.getLogger(RoomChatService.class);

    private static final String SYSTEM_PROMPT_PREFIX = """
            You are Codeleon, a coding assistant for a collaborative editor.
            Answer the user's question using the code excerpts below as context.
            If the excerpts do not contain enough information, say so plainly.
            Quote specific lines when relevant. Be concise — this is an editor sidebar.
            """;

    private final OllamaClient ollama;
    private final OllamaStreamer streamer;
    private final QdrantClient qdrant;

    public void streamChat(UUID roomId, ChatRequest request, SseEmitter emitter) {
        long startedAt = System.currentTimeMillis();
        try {
            // 1. Embed the query
            float[] queryVector = ollama.embed(request.query());

            // 2. Search top-K chunks scoped to this room
            Map<String, Object> filter = Map.of("must", List.of(
                    Map.of("key", "roomId", "match", Map.of("value", roomId.toString()))
            ));
            List<QdrantClient.ScoredPoint> hits = qdrant.search(queryVector, request.topKOrDefault(), filter);

            // 3. Push the context event so the UI can display retrieved chunks early
            emitter.send(SseEmitter.event().name("context").data(toContextPayload(hits)));

            // 4. Assemble messages: system + history + user
            List<OllamaClient.ChatMessage> messages = new ArrayList<>();
            messages.add(OllamaClient.ChatMessage.system(buildSystemPrompt(hits)));
            messages.addAll(request.historyOrEmpty());
            messages.add(OllamaClient.ChatMessage.user(request.query()));

            // 5. Stream tokens
            int[] tokenCount = {0};
            String full = streamer.streamChat(messages, token -> {
                tokenCount[0]++;
                try {
                    emitter.send(SseEmitter.event().name("token").data(token));
                } catch (IOException ex) {
                    throw new IllegalStateException("Client disconnected", ex);
                }
            });

            long durationMs = System.currentTimeMillis() - startedAt;
            log.info("Chat for room {} replied with {} tokens ({} chars) in {} ms",
                    roomId, tokenCount[0], full.length(), durationMs);

            emitter.send(SseEmitter.event().name("done").data(Map.of(
                    "tokens", tokenCount[0],
                    "characters", full.length(),
                    "durationMs", durationMs,
                    "contextChunks", hits.size()
            )));
            emitter.complete();
        } catch (Exception ex) {
            log.warn("Chat for room {} failed: {}", roomId, ex.getMessage());
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of(
                        "message", ex.getMessage() == null ? "Chat failed" : ex.getMessage()
                )));
            } catch (IOException ignored) {
                // client already gone
            }
            emitter.completeWithError(ex);
        }
    }

    static String buildSystemPrompt(List<QdrantClient.ScoredPoint> hits) {
        if (hits.isEmpty()) {
            return SYSTEM_PROMPT_PREFIX
                    + "\n(No code excerpts were found for this room. Tell the user the room appears empty.)";
        }
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT_PREFIX);
        sb.append("\n");
        for (int i = 0; i < hits.size(); i++) {
            QdrantClient.ScoredPoint h = hits.get(i);
            String path = String.valueOf(h.payload().getOrDefault("path", "unknown"));
            String text = String.valueOf(h.payload().getOrDefault("text", ""));
            sb.append("\n--- excerpt ").append(i + 1)
                    .append(" (path=").append(path)
                    .append(", score=").append(String.format("%.2f", h.score()))
                    .append(") ---\n")
                    .append(text)
                    .append("\n--- end excerpt ").append(i + 1).append(" ---\n");
        }
        return sb.toString();
    }

    static List<Map<String, Object>> toContextPayload(List<QdrantClient.ScoredPoint> hits) {
        List<Map<String, Object>> out = new ArrayList<>(hits.size());
        for (QdrantClient.ScoredPoint h : hits) {
            out.add(Map.of(
                    "path", h.payload().getOrDefault("path", "unknown"),
                    "score", h.score(),
                    "chunkIndex", h.payload().getOrDefault("chunkIndex", -1),
                    "preview", truncate(String.valueOf(h.payload().getOrDefault("text", "")), 200)
            ));
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
}
