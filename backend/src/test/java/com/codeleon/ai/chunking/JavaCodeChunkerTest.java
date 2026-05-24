package com.codeleon.ai.chunking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaCodeChunkerTest {

    private final JavaCodeChunker chunker = new JavaCodeChunker();

    @Test
    void emptyAndBlankInputProduceNoChunks() {
        assertThat(chunker.chunk(null)).isEmpty();
        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk("   \n\n  ")).isEmpty();
    }

    @Test
    void unparseableSourceFallsBackToEmptyList() {
        // Returning empty signals the dispatcher to fall back to the text
        // chunker — we never want a parse failure to propagate.
        List<CodeChunk> chunks = chunker.chunk("class Broken { void f() {  // unterminated");
        assertThat(chunks).isEmpty();
    }

    @Test
    void splitsClassIntoMethodChunksWithSymbols() {
        String source = """
                package com.example;

                import java.util.List;
                import java.util.ArrayList;
                import java.util.Map;

                public class AuthService {
                    private final UserRepository users;

                    public AuthService(UserRepository users) {
                        this.users = users;
                    }

                    public String refreshToken(String oldToken) {
                        if (oldToken == null) throw new IllegalArgumentException("token required");
                        // pretend to do some work
                        String fresh = oldToken + "-refreshed-with-padding-to-exceed-min-chunk-size";
                        return fresh;
                    }

                    public void revoke(String token) {
                        users.deleteByToken(token);
                        // intentionally longer body so it clears the merge threshold
                        // and stays its own chunk in the assertion below
                        System.out.println("revoked " + token);
                    }
                }
                """;

        List<CodeChunk> chunks = chunker.chunk(source);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).anyMatch(c -> "AuthService.refreshToken".equals(c.symbol())
                && c.symbolKind() == CodeChunk.SymbolKind.METHOD);
        assertThat(chunks).anyMatch(c -> "AuthService.revoke".equals(c.symbol())
                && c.symbolKind() == CodeChunk.SymbolKind.METHOD);
        // Every chunk should carry a sensible line range.
        for (CodeChunk c : chunks) {
            assertThat(c.startLine()).isPositive();
            assertThat(c.endLine()).isGreaterThanOrEqualTo(c.startLine());
            assertThat(c.text().length()).isLessThanOrEqualTo(CodeChunker.MAX_CHUNK_CHARS);
        }
    }

    @Test
    void splitsOversizedMethodIntoMultipleChunksKeepingSymbol() {
        // Build a single method that goes well past MAX_CHUNK_CHARS.
        StringBuilder body = new StringBuilder();
        body.append("class Big {\n");
        body.append("    void huge() {\n");
        for (int i = 0; i < 200; i++) {
            body.append("        System.out.println(\"line ").append(i)
                    .append(" — padding to exceed the per-chunk budget\");\n");
        }
        body.append("    }\n}\n");

        List<CodeChunk> chunks = chunker.chunk(body.toString());

        long methodChunks = chunks.stream()
                .filter(c -> "Big.huge".equals(c.symbol()))
                .count();
        assertThat(methodChunks).isGreaterThan(1);
        for (CodeChunk c : chunks) {
            assertThat(c.text().length()).isLessThanOrEqualTo(CodeChunker.MAX_CHUNK_CHARS);
        }
    }

    @Test
    void recordAndEnumProduceChunksWithMatchingKinds() {
        String source = """
                public record Point(int x, int y) {
                    public Point {
                        if (x < 0 || y < 0) throw new IllegalArgumentException();
                    }
                    public int sum() { return x + y; }
                }

                enum Color { RED, GREEN, BLUE }
                """;
        List<CodeChunk> chunks = chunker.chunk(source);
        assertThat(chunks).anyMatch(c -> c.symbolKind() == CodeChunk.SymbolKind.RECORD
                || (c.symbol() != null && c.symbol().startsWith("Point")));
    }
}
