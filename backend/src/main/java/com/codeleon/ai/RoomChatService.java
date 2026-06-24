package com.codeleon.ai;

import com.codeleon.ai.agent.AgentLoop;
import com.codeleon.ai.agent.AgentLoopResult;
import com.codeleon.ai.history.RoomChatHistoryService;
import com.codeleon.ai.history.RoomChatRole;
import com.codeleon.ai.metrics.AiMetricsService;
import com.codeleon.ai.retrieval.HybridRetriever;
import com.codeleon.ai.retrieval.RetrievedChunk;
import com.codeleon.user.User;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates RAG chat: runs hybrid retrieval (vector + BM25 fused with RRF)
 * for the room, builds a grounded prompt, and streams Ollama's response into
 * the provided {@link SseEmitter}.
 */
@Service
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

    /**
     * Aggregate char budget for the retrieved-excerpts section. Individual
     * chunks are capped (~1500 chars), but topK of them together can overflow
     * the model's context window — so we cap their sum and drop the
     * lowest-ranked overflow. ~8000 chars ≈ 2000 tokens, leaving room for the
     * active file, the run error, and the answer within an 8192-token num_ctx.
     */
    static final int MAX_EXCERPT_SECTION_CHARS = 8_000;

    private final OllamaStreamer streamer;
    private final HybridRetriever retriever;
    /**
     * Nullable so unit tests that mock only the streaming pipeline can
     * pass {@code null} via the constructor. When null, persistence is
     * skipped silently.
     */
    private final RoomChatHistoryService history;
    /** Nullable for the same reason — legacy unit tests pre-date the loop. */
    private final AgentLoop agentLoop;
    /** Nullable so unit tests can ignore metrics; production wiring supplies it. */
    private final AiMetricsService metrics;

    // @Autowired pins Spring to this constructor; the legacy 3-/4-arg
    // ctors below exist for unit tests that pre-date metrics/agent loop.
    @Autowired
    public RoomChatService(OllamaStreamer streamer, HybridRetriever retriever,
                            RoomChatHistoryService history, AgentLoop agentLoop,
                            AiMetricsService metrics) {
        this.streamer = streamer;
        this.retriever = retriever;
        this.history = history;
        this.agentLoop = agentLoop;
        this.metrics = metrics;
    }

    /** Backward-compatible constructor for tests pre-dating metrics. */
    public RoomChatService(OllamaStreamer streamer, HybridRetriever retriever,
                            RoomChatHistoryService history, AgentLoop agentLoop) {
        this(streamer, retriever, history, agentLoop, null);
    }

    /** Backward-compatible constructor for tests pre-dating the agent loop. */
    public RoomChatService(OllamaStreamer streamer, HybridRetriever retriever, RoomChatHistoryService history) {
        this(streamer, retriever, history, null, null);
    }

    public void streamChat(UUID roomId, User user, ChatRequest request, SseEmitter emitter) {
        long startedAt = System.currentTimeMillis();
        // 0. Persist the user's question right away so the conversation
        // thread is durable even if the stream fails before any reply.
        // Failure-swallowing — see RoomChatHistoryService.record.
        if (history != null) {
            history.record(roomId, user, RoomChatRole.USER, request.query());
        }

        // Agent mode short-circuit: the tool-using loop handles its own
        // prompt assembly and SSE events. The classic RAG flow below is
        // untouched so {@code mode="chat"} keeps the exact behaviour the
        // current frontend relies on.
        if (request.isAgentMode() && agentLoop != null) {
            runAgentMode(roomId, user, request, emitter, startedAt);
            return;
        }

        try {
            // 1. Hybrid retrieval: vector + BM25 fused with RRF and biased
            // toward the file the user currently has open. The retriever
            // owns embedding, vector search, and BM25 — RoomChatService
            // stays focused on prompt assembly and streaming.
            List<RetrievedChunk> hits = retriever.retrieve(
                    roomId,
                    request.query(),
                    request.topKOrDefault(),
                    request.hasActiveFile() ? request.activeFilePath() : null
            );

            // 2. Push the context event so the UI can display retrieved chunks early
            emitter.send(SseEmitter.event().name("context").data(toContextPayload(hits)));

            // 3. Assemble messages: system + history + user
            List<OllamaClient.ChatMessage> messages = new ArrayList<>();
            messages.add(OllamaClient.ChatMessage.system(buildSystemPrompt(hits, request)));
            messages.addAll(request.historyOrEmpty());
            messages.add(OllamaClient.ChatMessage.user(request.query()));

            // 4. Stream tokens. Wrapped in {"t": ...} so the SSE 'data:' line is
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

            // Persist the assistant's full reply alongside the user's
            // question saved at step 0. Done before `done` so a client
            // refresh right after the event sees a consistent thread.
            if (history != null) {
                history.record(roomId, user, RoomChatRole.ASSISTANT, full);
            }

            emitter.send(SseEmitter.event().name("done").data(Map.of(
                    "tokens", tokenCount[0],
                    "characters", full.length(),
                    "durationMs", durationMs,
                    "contextChunks", hits.size()
            )));
            if (metrics != null) metrics.recordTurn("chat", request.query(), durationMs, false);
            emitter.complete();
        } catch (Exception ex) {
            log.warn("Chat for room {} failed: {}", roomId, ex.getMessage());
            if (metrics != null) {
                metrics.recordTurn("chat", request.query(),
                        System.currentTimeMillis() - startedAt, true);
            }
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
     * Agent-mode driver: delegates to {@link AgentLoop} and translates its
     * stream of {@code tool_call} / {@code tool_result} events into SSE
     * events on the wire, then emits the final answer in one block as a
     * pseudo-token so the existing frontend renderer needs no changes.
     */
    private void runAgentMode(UUID roomId, User user, ChatRequest request, SseEmitter emitter, long startedAt) {
        try {
            AgentLoopResult result = agentLoop.run(roomId, request, (name, payload) -> {
                if (metrics != null && "tool_call".equals(name) && payload.get("name") != null) {
                    metrics.recordToolCall(payload.get("name").toString());
                }
                try {
                    emitter.send(SseEmitter.event().name(name).data(payload));
                } catch (IOException ex) {
                    throw new IllegalStateException("Client disconnected during agent step", ex);
                }
            });

            // Emit the final answer as one token chunk so the frontend's
            // existing token-handling code path renders it without a
            // dedicated "agent answer" event.
            emitter.send(SseEmitter.event().name("token").data(Map.of("t", result.answer())));

            if (history != null) {
                history.record(roomId, user, RoomChatRole.ASSISTANT, result.answer());
            }

            long durationMs = System.currentTimeMillis() - startedAt;
            log.info("Agent chat for room {} completed in {} iterations, {} tool calls, {} ms",
                    roomId, result.iterations(), result.toolCalls(), durationMs);
            if (metrics != null) {
                metrics.recordTurn("agent", request.query(), durationMs, false);
                metrics.recordAgentIterations(result.iterations());
            }
            emitter.send(SseEmitter.event().name("done").data(Map.of(
                    "tokens", 0,
                    "characters", result.answer().length(),
                    "durationMs", durationMs,
                    "contextChunks", 0,
                    "iterations", result.iterations(),
                    "toolCalls", result.toolCalls(),
                    "mode", "agent"
            )));
            emitter.complete();
        } catch (Exception ex) {
            log.warn("Agent chat for room {} failed: {}", roomId, ex.getMessage());
            if (metrics != null) {
                metrics.recordTurn("agent", request.query(),
                        System.currentTimeMillis() - startedAt, true);
            }
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of(
                        "message", ex.getMessage() == null ? "Agent failed" : ex.getMessage()
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
    static String buildSystemPrompt(List<RetrievedChunk> hits, ChatRequest request) {
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
        // Skip chunks from the file the user already has open: the full file
        // content is already embedded above as the "currently open file" block,
        // so re-shipping its chunks here just doubles tokens and confuses the
        // model (it sees the same code twice with two different framings).
        String activePath = request.hasActiveFile() ? request.activeFilePath() : null;
        int excerptChars = 0;
        int emitted = 0;
        for (int i = 0; i < hits.size(); i++) {
            RetrievedChunk h = hits.get(i);
            if (activePath != null && activePath.equals(h.path())) {
                continue;
            }
            String text = h.text() == null ? "" : h.text();
            // Aggregate context budget: keep the top hit always, then stop
            // adding once the excerpts section would exceed its budget. Hits
            // are sorted best-first, so the dropped ones are the weakest.
            if (emitted > 0 && excerptChars + text.length() > MAX_EXCERPT_SECTION_CHARS) {
                sb.append("\n--- (").append(hits.size() - i)
                        .append(" lower-ranked excerpt(s) omitted to fit the context budget) ---\n");
                break;
            }
            excerptChars += text.length();
            emitted++;

            sb.append("\n--- excerpt ").append(emitted)
                    .append(" (path=").append(h.path());
            if (h.symbol() != null) {
                sb.append(", symbol=").append(h.symbol());
            }
            if (h.hasLineRange()) {
                sb.append(", lines ").append(h.startLine()).append('-').append(h.endLine());
            }
            // Surface the retrieval provenance so the model can weigh
            // exact-identifier matches (BM25) versus semantic matches
            // (vector) when reasoning about how relevant an excerpt is.
            sb.append(", score=").append(String.format("%.2f", h.finalScore()))
                    .append(", source=").append(provenance(h))
                    .append(") ---\n")
                    .append(text)
                    .append("\n--- end excerpt ").append(emitted).append(" ---\n");
        }
        return sb.toString();
    }

    private static String provenance(RetrievedChunk h) {
        if (h.fromVector() && h.fromBm25()) return "vector+bm25";
        if (h.fromVector()) return "vector";
        if (h.fromBm25()) return "bm25";
        return "unknown";
    }

    static List<Map<String, Object>> toContextPayload(List<RetrievedChunk> hits) {
        List<Map<String, Object>> out = new ArrayList<>(hits.size());
        for (RetrievedChunk h : hits) {
            // LinkedHashMap because optional fields (symbol, lines) are
            // written only when present — Map.of cannot hold null values
            // and stops at 10 pairs, neither of which is acceptable here.
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", h.path());
            entry.put("score", h.finalScore());
            entry.put("chunkIndex", h.chunkIndex());
            entry.put("preview", truncate(h.text() == null ? "" : h.text(), 200));

            if (h.symbol() != null) entry.put("symbol", h.symbol());
            if (h.symbolKind() != null) entry.put("symbolKind", h.symbolKind());
            if (h.startLine() != null) entry.put("startLine", h.startLine());
            if (h.endLine() != null) entry.put("endLine", h.endLine());

            // Expose retrieval provenance to the UI so the context drawer
            // can show why a chunk surfaced (vector / bm25 / both).
            List<String> source = new ArrayList<>(2);
            if (h.fromVector()) source.add("vector");
            if (h.fromBm25()) source.add("bm25");
            entry.put("source", source);

            out.add(entry);
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
