package com.codeleon.ai.agent.tools;

import com.codeleon.ai.OllamaClient;
import com.codeleon.ai.QdrantClient;
import com.codeleon.ai.agent.ToolExecutionException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticSearchToolTest {

    @Test
    void missingQueryArgumentRaisesError() {
        SemanticSearchTool tool = new SemanticSearchTool(mock(OllamaClient.class), mock(QdrantClient.class));
        assertThatThrownBy(() -> tool.execute(UUID.randomUUID(), Map.of()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("query");
    }

    @Test
    void emptyResultsReturnsExplanatoryFallback() throws Exception {
        OllamaClient ollama = mock(OllamaClient.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        when(ollama.embed(any())).thenReturn(new float[]{0.1f, 0.2f});
        when(qdrant.search(any(), anyInt(), any())).thenReturn(List.of());

        String out = new SemanticSearchTool(ollama, qdrant)
                .execute(UUID.randomUUID(), Map.of("query", "auth flow"));
        assertThat(out).contains("No semantically-matching");
    }

    @Test
    void surfacesPayloadSymbolPathAndLineRange() throws Exception {
        OllamaClient ollama = mock(OllamaClient.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        when(ollama.embed(eq("auth flow"))).thenReturn(new float[]{0.1f});
        when(qdrant.search(any(), anyInt(), any())).thenReturn(List.of(
                new QdrantClient.ScoredPoint(UUID.randomUUID(), 0.93d, Map.of(
                        "path", "auth/AuthService.java",
                        "symbol", "AuthService.login",
                        "startLine", 42,
                        "endLine", 60,
                        "text", "public Session login(String email, String pw) { ... }"
                ))
        ));

        String out = new SemanticSearchTool(ollama, qdrant)
                .execute(UUID.randomUUID(), Map.of("query", "auth flow"));
        assertThat(out).contains("AuthService.login");
        assertThat(out).contains("auth/AuthService.java");
        assertThat(out).contains("L42-L60");
        assertThat(out).contains("0.93");
    }

    @Test
    void embeddingFailureMapsToToolException() {
        OllamaClient ollama = mock(OllamaClient.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        when(ollama.embed(any())).thenThrow(new IllegalStateException("Ollama down"));

        SemanticSearchTool tool = new SemanticSearchTool(ollama, qdrant);
        assertThatThrownBy(() -> tool.execute(UUID.randomUUID(), Map.of("query", "anything")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("Embedding failed");
    }
}
