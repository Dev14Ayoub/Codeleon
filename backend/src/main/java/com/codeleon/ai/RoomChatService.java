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
            You are Codeleon, a coding assistant inside a collaborative code editor.
            Use the context below: the file the user currently has open, the error
            from their last run (if any), and retrieved excerpts from the project.
            When the user has a bug or an error, give a concrete fix — show the
            corrected code, not just an explanation. Quote specific lines when
            relevant. If the context is not enough, say so plainly. Be concise —
            this is an editor sidebar, not a documentation page.
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
            messages.add(OllamaClient.ChatMessage.system(buildSystemPrompt(hits, request)));
            messages.addAll(request.historyOrEmpty());
            messages.add(OllamaClient.ChatMessage.user(request.query()));

            // 5. Stream tokens. Wrapped in {"t": ...} so the SSE 'data:' line is
            // always JSON — avoids the spec's leading-space stripping eating
            // whitespace at token boundaries.
            int[] tokenCount = {0};
            String full = streamer.streamChat(messages, token -> {
                tokenCount[0]++;
                try {
                    emitter.send(SseEmitter.event().name("token").data(Map.of("t", token)));
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

    /**
     * Builds the system prompt from three context layers, most-specific
     * first: the file the user currently has open, the error from their
     * last run, then the RAG excerpts retrieved from the wider project.
     * The direct layers (active file, run error) matter most because the
     * RAG index can be stale or miss unsaved edits — they are what let
     * the assistant answer "why is THIS broken" instead of guessing.
     */
    static String buildSystemPrompt(List<QdrantClient.ScoredPoint> hits, ChatRequest request) {
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT_PREFIX);

        if (request.hasActiveFile()) {
            sb.append("\n\n--- currently open file (").append(request.activeFilePath()).append(") ---\n")
                    .append(cap(request.activeFileContent(), ChatRequest.MAX_ACTIVE_FILE_CHARS))
                    .append("\n--- end currently open file ---\n");
        }

        if (request.hasRunError()) {
            sb.append("\n--- error from the user's last run ---\n")
                    .append(cap(request.lastRunStderr(), ChatRequest.MAX_RUN_STDERR_CHARS))
                    .append("\n--- end error ---\n");
        }

        if (hits.isEmpty()) {
            if (!request.hasActiveFile()) {
                // Nothing indexed and no open file — there is genuinely
                // no code context to work from.
                sb.append("\n(No project excerpts were found and no file is open. "
                        + "Tell the user the room appears empty.)");
            }
            return sb.toString();
        }

        sb.append("\n--- retrieved project excerpts ---\n");
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

    /**
     * Caps a context block to a char budget. Unlike {@link #truncate}
     * (used for short UI previews) this keeps the head of the content —
     * the start of a file or a stack trace is the part the model needs —
     * and appends an explicit marker so the model knows it is partial.
     */
    private static String cap(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n... [truncated, " + (s.length() - max) + " more chars]";
    }
}
