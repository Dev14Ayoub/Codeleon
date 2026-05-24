package com.codeleon.ai.agent.tools;

import com.codeleon.ai.chunking.CodeChunk;
import com.codeleon.ai.retrieval.Bm25Searcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchCodeToolTest {

    private Bm25Searcher bm25;

    @BeforeEach
    void setUp() {
        bm25 = new Bm25Searcher();
    }

    @AfterEach
    void tearDown() {
        bm25.shutdown();
    }

    @Test
    void missingQueryArgumentRaisesError() {
        SearchCodeTool tool = new SearchCodeTool(bm25);
        assertThatThrownBy(() -> tool.execute(UUID.randomUUID(), Map.of()))
                .hasMessageContaining("query");
    }

    @Test
    void emptyRoomReturnsNoMatchesMessage() throws Exception {
        SearchCodeTool tool = new SearchCodeTool(bm25);
        String out = tool.execute(UUID.randomUUID(), Map.of("query", "anything"));
        assertThat(out).contains("No matches");
    }

    @Test
    void surfacesIndexedSymbolWithLineRange() throws Exception {
        UUID room = UUID.randomUUID();
        bm25.upsertFile(room, "Auth.java", List.of(
                new CodeChunk(
                        "public String refreshToken(String token) { return token + \":fresh\"; }",
                        "AuthService.refreshToken", CodeChunk.SymbolKind.METHOD, 12, 14)
        ));

        String out = new SearchCodeTool(bm25).execute(room, Map.of("query", "refreshToken"));
        assertThat(out).contains("AuthService.refreshToken");
        assertThat(out).contains("Auth.java");
        assertThat(out).contains("L12-L14");
    }
}
