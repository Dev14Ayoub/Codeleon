package com.codeleon.ai.agent;

import com.codeleon.ai.ChatRequest;
import com.codeleon.ai.OllamaClient;
import com.codeleon.ai.OllamaClient.ChatMessage;
import com.codeleon.ai.OllamaClient.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentLoopTest {

    private static ChatRequest queryOnly(String query) {
        return new ChatRequest(query, null, null, null, null, null, "agent");
    }

    private static class StubTool implements AgentTool {
        private final String name;
        private final String result;
        int calls = 0;
        Map<String, Object> lastArgs;
        StubTool(String name, String result) {
            this.name = name;
            this.result = result;
        }
        @Override public String name() { return name; }
        @Override public String description() { return "stub " + name; }
        @Override public Map<String, Object> parametersSchema() { return Map.of("type", "object", "properties", Map.of()); }
        @Override public String execute(UUID roomId, Map<String, Object> arguments) {
            calls++;
            lastArgs = arguments;
            return result;
        }
    }

    @Test
    void firstResponseWithoutToolCallsIsTheAnswer() {
        OllamaClient ollama = mock(OllamaClient.class);
        AgentToolRegistry registry = new AgentToolRegistry(List.of(new StubTool("noop", "ok")));

        when(ollama.chatWithTools(anyList(), anyList()))
                .thenReturn(ChatMessage.assistant("Answered directly."));

        AgentLoop loop = new AgentLoop(ollama, registry);
        List<String> events = new ArrayList<>();
        AgentLoopResult result = loop.run(UUID.randomUUID(), queryOnly("hi"),
                (name, payload) -> events.add(name));

        assertThat(result.answer()).isEqualTo("Answered directly.");
        assertThat(result.toolCalls()).isZero();
        assertThat(result.iterations()).isEqualTo(1);
        assertThat(events).isEmpty();
    }

    @Test
    void toolCallsAreExecutedAndResultsFedBackToModel() {
        OllamaClient ollama = mock(OllamaClient.class);
        StubTool listFiles = new StubTool("list_files", "App.java\nUser.java\n");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(listFiles));

        // First call returns a tool_call; second returns the final answer.
        ChatMessage firstResponse = new ChatMessage(
                "assistant", "", null,
                List.of(new ToolCall(new ToolCall.Function("list_files", Map.of())))
        );
        when(ollama.chatWithTools(anyList(), anyList()))
                .thenReturn(firstResponse)
                .thenReturn(ChatMessage.assistant("There are 2 files: App.java, User.java."));

        AgentLoop loop = new AgentLoop(ollama, registry);
        List<String> events = new ArrayList<>();
        AgentLoopResult result = loop.run(UUID.randomUUID(), queryOnly("what files?"),
                (name, payload) -> events.add(name));

        assertThat(listFiles.calls).isEqualTo(1);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.answer()).contains("2 files");
        // We expect exactly one tool_call + one tool_result event for this turn.
        assertThat(events).containsExactly("tool_call", "tool_result");
    }

    @Test
    void unknownToolEmitsErrorResultButDoesNotCrashLoop() {
        OllamaClient ollama = mock(OllamaClient.class);
        AgentToolRegistry registry = new AgentToolRegistry(List.of(new StubTool("list_files", "ok")));

        ChatMessage bogusCall = new ChatMessage(
                "assistant", "", null,
                List.of(new ToolCall(new ToolCall.Function("nonexistent_tool", Map.of())))
        );
        when(ollama.chatWithTools(anyList(), anyList()))
                .thenReturn(bogusCall)
                .thenReturn(ChatMessage.assistant("Recovered."));

        AgentLoop loop = new AgentLoop(ollama, registry);
        List<Object> resultPayloads = new ArrayList<>();
        AgentLoopResult result = loop.run(UUID.randomUUID(), queryOnly("anything"),
                (name, payload) -> {
                    if ("tool_result".equals(name)) resultPayloads.add(payload);
                });

        assertThat(resultPayloads).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstResult = (Map<String, Object>) resultPayloads.get(0);
        assertThat(firstResult.get("error")).isEqualTo(true);
        assertThat(firstResult.get("content").toString()).contains("Unknown tool");
        assertThat(result.answer()).isEqualTo("Recovered.");
    }

    @Test
    void hittingIterationCapForcesFinalAnswer() {
        OllamaClient ollama = mock(OllamaClient.class);
        StubTool tool = new StubTool("list_files", "stub");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));

        // Always return a tool_call so the loop never converges on its own —
        // exercises the "forced final answer" branch.
        ChatMessage loopForever = new ChatMessage(
                "assistant", "", null,
                List.of(new ToolCall(new ToolCall.Function("list_files", Map.of())))
        );
        // The forced final call (with an empty tool catalogue) returns the answer.
        when(ollama.chatWithTools(anyList(), anyList()))
                .thenReturn(loopForever)
                .thenReturn(loopForever)
                .thenReturn(loopForever)
                .thenReturn(loopForever)
                .thenReturn(loopForever)
                .thenReturn(ChatMessage.assistant("Forced."));

        AgentLoop loop = new AgentLoop(ollama, registry);
        AgentLoopResult result = loop.run(UUID.randomUUID(), queryOnly("..."),
                (name, payload) -> {});

        assertThat(tool.calls).isEqualTo(AgentLoop.MAX_ITERATIONS);
        assertThat(result.answer()).isEqualTo("Forced.");
        verify(ollama, times(AgentLoop.MAX_ITERATIONS + 1)).chatWithTools(anyList(), any());
    }

    @Test
    void contentJsonIsLiftedToToolCallWhenOllamaForgetsTheStructuredField() {
        // Repro of the Ollama 0.24 + qwen2.5-coder Q4 behaviour: the model
        // emits the tool intent as plain JSON inside content, with no
        // <tool_call> wrapper and no structured tool_calls array. We must
        // still execute the tool — silently dropping it would make the
        // entire agent mode appear broken on these model/version combos.
        OllamaClient ollama = mock(OllamaClient.class);
        StubTool listFiles = new StubTool("list_files", "App.java\n");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(listFiles));

        ChatMessage contentOnly = ChatMessage.assistant("{\"name\": \"list_files\", \"arguments\": {}}");
        when(ollama.chatWithTools(anyList(), anyList()))
                .thenReturn(contentOnly)
                .thenReturn(ChatMessage.assistant("Done."));

        AgentLoop loop = new AgentLoop(ollama, registry);
        AgentLoopResult result = loop.run(UUID.randomUUID(), queryOnly("what files?"), (n, p) -> {});

        assertThat(listFiles.calls).isEqualTo(1);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.answer()).isEqualTo("Done.");
    }

    @Test
    void toolCallWrappersInContentAreExtractedAsFallback() {
        // Canonical Qwen template path: model emits <tool_call>{...}</tool_call>
        // and Ollama doesn't strip it into the structured field. We pick the
        // wrapper apart and execute the call.
        OllamaClient ollama = mock(OllamaClient.class);
        StubTool listFiles = new StubTool("list_files", "App.java\n");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(listFiles));

        String wrapped = "<tool_call>\n{\"name\": \"list_files\", \"arguments\": {}}\n</tool_call>";
        when(ollama.chatWithTools(anyList(), anyList()))
                .thenReturn(ChatMessage.assistant(wrapped))
                .thenReturn(ChatMessage.assistant("Done."));

        AgentLoop loop = new AgentLoop(ollama, registry);
        AgentLoopResult result = loop.run(UUID.randomUUID(), queryOnly("what files?"), (n, p) -> {});

        assertThat(listFiles.calls).isEqualTo(1);
        assertThat(result.answer()).isEqualTo("Done.");
    }

    @Test
    void contentExtractorRejectsUnknownToolNamesAndTreatsThemAsFinalAnswer() {
        // Defence-in-depth: a chatty model can't smuggle in a fake tool
        // name (e.g. "rm_rf") by pretending to call it via plain JSON.
        // Unknown names are ignored and the content stands as the answer.
        OllamaClient ollama = mock(OllamaClient.class);
        AgentToolRegistry registry = new AgentToolRegistry(List.of(new StubTool("list_files", "ok")));

        String fake = "{\"name\": \"rm_rf\", \"arguments\": {\"path\": \"/\"}}";
        when(ollama.chatWithTools(anyList(), anyList()))
                .thenReturn(ChatMessage.assistant(fake));

        AgentLoop loop = new AgentLoop(ollama, registry);
        AgentLoopResult result = loop.run(UUID.randomUUID(), queryOnly("hi"), (n, p) -> {});

        assertThat(result.toolCalls()).isZero();
        assertThat(result.iterations()).isEqualTo(1);
        assertThat(result.answer()).isEqualTo(fake);
    }

    @Test
    void oversizedToolResponseIsTruncatedBeforeBeingFedBack() {
        OllamaClient ollama = mock(OllamaClient.class);
        String huge = "x".repeat(AgentLoop.MAX_TOOL_RESPONSE_CHARS + 500);
        StubTool tool = new StubTool("list_files", huge);
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));

        ChatMessage call = new ChatMessage("assistant", "", null,
                List.of(new ToolCall(new ToolCall.Function("list_files", Map.of()))));
        when(ollama.chatWithTools(anyList(), anyList()))
                .thenReturn(call)
                .thenReturn(ChatMessage.assistant("ok"));

        AgentLoop loop = new AgentLoop(ollama, registry);
        List<Object> resultPayloads = new ArrayList<>();
        loop.run(UUID.randomUUID(), queryOnly("dump"),
                (name, payload) -> {
                    if ("tool_result".equals(name)) resultPayloads.add(payload);
                });

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) resultPayloads.get(0);
        String emittedContent = r.get("content").toString();
        assertThat(emittedContent).contains("[truncated,");
        assertThat(emittedContent.length()).isLessThan(huge.length());
    }
}
