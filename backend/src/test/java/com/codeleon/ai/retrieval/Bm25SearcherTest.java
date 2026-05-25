package com.codeleon.ai.retrieval;

import com.codeleon.ai.chunking.CodeChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25SearcherTest {

    private Bm25Searcher searcher;

    @BeforeEach
    void setUp() {
        searcher = new Bm25Searcher();
    }

    @AfterEach
    void tearDown() {
        searcher.shutdown();
    }

    @Test
    void searchOnUnknownRoomReturnsEmpty() {
        assertThat(searcher.search(UUID.randomUUID(), "anything", 5)).isEmpty();
    }

    @Test
    void emptyQueryReturnsEmpty() {
        UUID room = UUID.randomUUID();
        searcher.upsertFile(room, "Foo.java", List.of(
                new CodeChunk("class Foo {}", "Foo", CodeChunk.SymbolKind.CLASS, 1, 1)
        ));
        assertThat(searcher.search(room, "", 5)).isEmpty();
        assertThat(searcher.search(room, "   ", 5)).isEmpty();
    }

    @Test
    void exactSymbolMatchScoresHighest() {
        UUID room = UUID.randomUUID();
        searcher.upsertFile(room, "Auth.java", List.of(
                new CodeChunk(
                        "public String refreshToken(String token) { return token + \":fresh\"; }",
                        "AuthService.refreshToken", CodeChunk.SymbolKind.METHOD, 12, 14),
                new CodeChunk(
                        "public boolean verify(String token) { return token != null; }",
                        "AuthService.verify", CodeChunk.SymbolKind.METHOD, 16, 18)
        ));

        List<Bm25Searcher.Hit> hits = searcher.search(room, "refreshToken", 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).symbol()).isEqualTo("AuthService.refreshToken");
        assertThat(hits.get(0).score()).isGreaterThan(0);
    }

    @Test
    void upsertReplacesExistingChunksForSamePath() {
        UUID room = UUID.randomUUID();
        // First upsert: register a method we will later replace.
        searcher.upsertFile(room, "Auth.java", List.of(
                new CodeChunk("void oldMethod() {}", "Auth.oldMethod", CodeChunk.SymbolKind.METHOD, 1, 1)
        ));
        assertThat(searcher.search(room, "oldMethod", 5)).hasSize(1);

        // Second upsert for the same path drops the old chunk entirely —
        // searching for the old method should now return nothing.
        searcher.upsertFile(room, "Auth.java", List.of(
                new CodeChunk("void brandNewMethod() {}", "Auth.brandNewMethod", CodeChunk.SymbolKind.METHOD, 1, 1)
        ));
        assertThat(searcher.search(room, "oldMethod", 5)).isEmpty();
        assertThat(searcher.search(room, "brandNewMethod", 5)).hasSize(1);
    }

    @Test
    void deletePathRemovesOnlyThatFile() {
        UUID room = UUID.randomUUID();
        searcher.upsertFile(room, "A.java", List.of(
                new CodeChunk("void unique_marker_alpha() {}", "A.alpha", CodeChunk.SymbolKind.METHOD, 1, 1)
        ));
        searcher.upsertFile(room, "B.java", List.of(
                new CodeChunk("void unique_marker_beta() {}", "B.beta", CodeChunk.SymbolKind.METHOD, 1, 1)
        ));

        searcher.deletePath(room, "A.java");

        assertThat(searcher.search(room, "unique_marker_alpha", 5)).isEmpty();
        assertThat(searcher.search(room, "unique_marker_beta", 5)).hasSize(1);
    }

    @Test
    void searchAndUpsertAfterDeleteRoomDegradeGracefully() {
        UUID room = UUID.randomUUID();
        searcher.upsertFile(room, "A.java", List.of(
                new CodeChunk("class A {}", "A", CodeChunk.SymbolKind.CLASS, 1, 1)
        ));
        searcher.deleteRoom(room);

        // After deleteRoom the state is gone — a search on the same room
        // must return empty rather than touching the closed writer
        // (anti-regression for the race where a thread held a stale
        // RoomState reference past the rooms.remove call).
        assertThat(searcher.search(room, "A", 5)).isEmpty();

        // An upsert right after a delete re-creates the room cleanly.
        // Without the closed-flag check, the stale state would still be
        // visible to a thread that captured it before the remove and
        // would explode on its closed writer.
        searcher.upsertFile(room, "B.java", List.of(
                new CodeChunk("class B {}", "B", CodeChunk.SymbolKind.CLASS, 1, 1)
        ));
        assertThat(searcher.search(room, "B", 5)).hasSize(1);
    }

    @Test
    void deleteRoomWipesEverything() {
        UUID room = UUID.randomUUID();
        searcher.upsertFile(room, "A.java", List.of(
                new CodeChunk("class Anything {}", "Anything", CodeChunk.SymbolKind.CLASS, 1, 1)
        ));
        searcher.deleteRoom(room);
        assertThat(searcher.search(room, "Anything", 5)).isEmpty();
    }

    @Test
    void specialCharactersInQueryDoNotThrow() {
        UUID room = UUID.randomUUID();
        searcher.upsertFile(room, "X.java", List.of(
                new CodeChunk("System.out.println(\"hi\");", "X.run", CodeChunk.SymbolKind.METHOD, 1, 1)
        ));
        // Lucene reserves these characters — unescaped they would explode
        // the parser. The searcher must escape user input so the chat
        // pipeline never crashes because someone typed an opening paren,
        // a colon, or a wildcard in their query. We assert non-null (no
        // throw) rather than non-empty — token matching is a separate
        // concern handled by analyzer choice.
        assertThat(searcher.search(room, "println", 5)).isNotEmpty();
        assertThat(searcher.search(room, "println(", 5)).isNotNull();
        assertThat(searcher.search(room, "foo:bar", 5)).isNotNull();
        assertThat(searcher.search(room, "(", 5)).isNotNull();
        assertThat(searcher.search(room, "?*", 5)).isNotNull();
    }
}
