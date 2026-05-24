package com.codeleon.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A chat turn from the editor sidebar.
 *
 * Beyond the query itself, the frontend ships three optional pieces of
 * "live" context that the RAG index cannot provide on its own:
 *   - activeFilePath / activeFileContent: the file the user is looking
 *     at right now. The backend never sees Y.Doc content otherwise, so
 *     without this the assistant is blind to unsaved/unindexed edits.
 *   - lastRunStderr: the error output of the most recent Run, if any.
 *     This is what turns "why is my code broken" into a task the model
 *     can actually do well.
 * activeFileContent and lastRunStderr are intentionally NOT bean-
 * validated for length: a large open file or a noisy stack trace is
 * legitimate input and must not fail the whole chat with a 400. They
 * are truncated server-side instead (see RoomChatService) so they
 * cannot blow past the small model's context window.
 */
public record ChatRequest(
        @NotBlank @Size(max = 4_000) String query,
        Integer topK,
        List<OllamaClient.ChatMessage> history,
        @Size(max = 255) String activeFilePath,
        String activeFileContent,
        String lastRunStderr,
        /** "chat" (default) → classic RAG; "agent" → tool-using loop. */
        String mode
) {
    /** Server-side caps applied by RoomChatService before prompting. */
    public static final int MAX_ACTIVE_FILE_CHARS = 16_000;
    public static final int MAX_RUN_STDERR_CHARS = 4_000;

    public static final String MODE_CHAT = "chat";
    public static final String MODE_AGENT = "agent";

    /**
     * Backward-compatible 6-arg ctor: callers that pre-date the agent
     * loop default to {@code mode="chat"} so behaviour is unchanged.
     */
    public ChatRequest(String query, Integer topK, List<OllamaClient.ChatMessage> history,
                       String activeFilePath, String activeFileContent, String lastRunStderr) {
        this(query, topK, history, activeFilePath, activeFileContent, lastRunStderr, null);
    }

    public int topKOrDefault() {
        if (topK == null || topK <= 0) return 5;
        return Math.min(topK, 20);
    }

    public List<OllamaClient.ChatMessage> historyOrEmpty() {
        return history == null ? List.of() : history;
    }

    public boolean hasActiveFile() {
        return activeFilePath != null && !activeFilePath.isBlank()
                && activeFileContent != null && !activeFileContent.isBlank();
    }

    public boolean hasRunError() {
        return lastRunStderr != null && !lastRunStderr.isBlank();
    }

    public boolean isAgentMode() {
        return mode != null && MODE_AGENT.equalsIgnoreCase(mode);
    }
}
