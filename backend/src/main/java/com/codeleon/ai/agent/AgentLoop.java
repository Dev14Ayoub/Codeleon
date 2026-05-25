package com.codeleon.ai.agent;

import com.codeleon.ai.ChatRequest;
import com.codeleon.ai.OllamaClient;
import com.codeleon.ai.OllamaClient.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The reasoning loop behind {@code mode="agent"} chats. On each iteration
 * the model decides whether to answer or to call a tool. If it calls a
 * tool, we run it, feed the result back, and let the model decide again.
 * The loop exits when the model emits an answer with no tool calls, or
 * after {@link #MAX_ITERATIONS} steps — whichever comes first.
 *
 * <p>Streaming note: each tool turn uses non-streaming chat because tool
 * calls arrive as JSON that is painful to parse incrementally. The final
 * assistant message — the answer the user actually reads — is emitted as
 * one block. We could re-issue it through {@code OllamaStreamer} for
 * token-by-token rendering, but that doubles the cost of the final step
 * and the UX is already dominated by tool calls being visible inline.
 */
@Component
@RequiredArgsConstructor
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /** Hard cap on tool-using iterations per chat turn. Picked high enough
     *  to let the model chain a few searches but low enough that a confused
     *  model can't loop forever. */
    public static final int MAX_ITERATIONS = 5;

    /** Per-tool-response character cap. Beyond this we truncate before
     *  feeding the result back to the model so a single fat file dump
     *  cannot blow past the context window. */
    public static final int MAX_TOOL_RESPONSE_CHARS = 4_000;

    private static final String SYSTEM_PROMPT_PREFIX = """
            You are Codeleon, a coding agent inside a collaborative code editor.
            You have tools to read the project's files and search for code.

            How to answer:
            1. If the user's question is generic and not about THIS project, answer from your own knowledge — do not call tools.
            2. If the question is about the project, USE TOOLS to look at the code before answering. Prefer search_code for exact identifiers (function names, variables), semantic_search for natural-language queries, and read_file for full file inspection.
            3. Call tools one step at a time. Read the result before deciding the next call. Do not guess paths or symbols.
            4. When you have enough context, answer concisely with concrete code excerpts and cite file paths + line numbers.

            Be efficient: fewer tool calls is better. Five tool-using iterations maximum per turn.
            """;

    private final OllamaClient ollama;
    private final AgentToolRegistry registry;

    /**
     * Runs the agentic loop end-to-end and emits events as it goes.
     *
     * @param roomId    scoping context — every tool call is bounded to this room
     * @param request   the chat turn (carries history, active file, last run error)
     * @param onEvent   sink for streaming events: ("tool_call"|"tool_result", payload)
     * @return the final assistant answer plus stats
     */
    public AgentLoopResult run(UUID roomId, ChatRequest request, BiConsumer<String, Map<String, Object>> onEvent) {
        long startedAt = System.currentTimeMillis();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(buildSystemPrompt(request)));
        messages.addAll(request.historyOrEmpty());
        messages.add(ChatMessage.user(request.query()));

        int totalToolCalls = 0;
        int iterations = 0;
        String finalAnswer = "";

        List<Map<String, Object>> toolCatalogue = registry.toOllamaTools();

        for (iterations = 1; iterations <= MAX_ITERATIONS; iterations++) {
            ChatMessage response = ollama.chatWithTools(messages, toolCatalogue);

            if (!response.hasToolCalls()) {
                finalAnswer = response.content() == null ? "" : response.content();
                messages.add(response);
                break;
            }

            messages.add(response);
            for (OllamaClient.ToolCall tc : response.toolCalls()) {
                totalToolCalls++;
                String callId = "call_" + totalToolCalls;
                String fnName = tc.function() == null ? "" : tc.function().name();
                Map<String, Object> args = (tc.function() != null && tc.function().arguments() != null)
                        ? tc.function().arguments() : Map.of();

                Map<String, Object> callEvent = new LinkedHashMap<>();
                callEvent.put("id", callId);
                callEvent.put("name", fnName);
                callEvent.put("arguments", args);
                onEvent.accept("tool_call", callEvent);

                ToolOutcome outcome = invokeTool(fnName, roomId, args);

                Map<String, Object> resultEvent = new LinkedHashMap<>();
                resultEvent.put("id", callId);
                resultEvent.put("name", fnName);
                resultEvent.put("content", outcome.content());
                resultEvent.put("error", outcome.isError());
                onEvent.accept("tool_result", resultEvent);

                messages.add(ChatMessage.tool(fnName, outcome.content()));
            }
        }

        if (finalAnswer.isBlank()) {
            // We exhausted the loop without the model committing to an
            // answer. Force one more call with the tool catalogue empty
            // so the model HAS to answer rather than schedule another
            // tool call we will not run.
            log.info("Agent loop for room {} hit iteration cap; forcing final answer", roomId);
            messages.add(ChatMessage.system(
                    "You have reached the maximum tool call budget. Answer the user now using what you have found."));
            ChatMessage forced = ollama.chatWithTools(messages, List.of());
            finalAnswer = forced.content() == null ? "(no answer available)" : forced.content();
        }

        long durationMs = System.currentTimeMillis() - startedAt;
        return new AgentLoopResult(finalAnswer, totalToolCalls, iterations, durationMs);
    }

    private ToolOutcome invokeTool(String name, UUID roomId, Map<String, Object> args) {
        AgentTool tool = registry.get(name);
        if (tool == null) {
            return new ToolOutcome("Unknown tool: " + name + " (available: " + registry.toOllamaTools().size() + ")", true);
        }
        try {
            String result = tool.execute(roomId, args);
            if (result == null) result = "(empty)";
            if (result.length() > MAX_TOOL_RESPONSE_CHARS) {
                int overflow = result.length() - MAX_TOOL_RESPONSE_CHARS;
                result = result.substring(0, MAX_TOOL_RESPONSE_CHARS)
                        + "\n... [truncated, " + overflow + " more chars]";
            }
            return new ToolOutcome(result, false);
        } catch (ToolExecutionException ex) {
            log.debug("Tool {} reported error: {}", name, ex.getMessage());
            return new ToolOutcome("Error: " + ex.getMessage(), true);
        } catch (RuntimeException ex) {
            log.warn("Tool {} threw unexpectedly: {}", name, ex.getMessage());
            return new ToolOutcome("Internal tool error: " + ex.getMessage(), true);
        }
    }

    static String buildSystemPrompt(ChatRequest request) {
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT_PREFIX);
        if (request.hasActiveFile()) {
            sb.append("\nThe file the user currently has open: ")
                    .append(request.activeFilePath())
                    .append('\n');
        }
        if (request.hasRunError()) {
            sb.append("\nError from their last run (first 4000 chars):\n```\n")
                    .append(request.lastRunStderr().length() > ChatRequest.MAX_RUN_STDERR_CHARS
                            ? request.lastRunStderr().substring(0, ChatRequest.MAX_RUN_STDERR_CHARS) + "\n... [truncated]"
                            : request.lastRunStderr())
                    .append("\n```\n");
        }
        return sb.toString();
    }

    private record ToolOutcome(String content, boolean isError) {}
}
