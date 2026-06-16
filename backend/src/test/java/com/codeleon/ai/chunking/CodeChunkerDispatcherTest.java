package com.codeleon.ai.chunking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeChunkerDispatcherTest {

    private final CodeChunkerDispatcher dispatcher = new CodeChunkerDispatcher();

    @Test
    void routesJavaPathThroughAstChunker() {
        String source = """
                public class Foo {
                    public int bar() {
                        // body padded so it clears the merge threshold
                        int x = 1;
                        int y = 2;
                        return x + y;
                    }
                }
                """;
        List<CodeChunk> chunks = dispatcher.chunk("src/Foo.java", source);
        assertThat(chunks).anyMatch(c -> "Foo.bar".equals(c.symbol()));
    }

    @Test
    void routesPythonPathThroughRegexChunker() {
        String source = """
                def hello(name):
                    print(f'hello {name}')
                    return name
                """;
        List<CodeChunk> chunks = dispatcher.chunk("script.py", source);
        assertThat(chunks).anyMatch(c -> "hello".equals(c.symbol())
                && c.symbolKind() == CodeChunk.SymbolKind.FUNCTION);
    }

    @Test
    void unknownExtensionUsesFallbackTextChunker() {
        // path="main" routes to the fallback text chunker. 3600 'a's →
        // 3 windows of 1500 with 150 overlap (step 1350).
        String text = "a".repeat(3600);
        List<CodeChunk> chunks = dispatcher.chunk("main", text);
        assertThat(chunks).hasSize(3);
        assertThat(chunks).allMatch(c -> c.symbolKind() == CodeChunk.SymbolKind.TEXT);
    }

    @Test
    void emptyTextReturnsEmptyList() {
        assertThat(dispatcher.chunk("Foo.java", "")).isEmpty();
        assertThat(dispatcher.chunk("Foo.java", "   \n")).isEmpty();
        assertThat(dispatcher.chunk("Foo.java", null)).isEmpty();
    }

    @Test
    void unparseableJavaFallsBackToTextChunks() {
        // Broken Java — the JavaCodeChunker emits an empty list and the
        // dispatcher should degrade to the fallback so the file is still
        // searchable rather than silently dropped from the index.
        String broken = "class Broken { void f() {  // unterminated\n".repeat(80);
        List<CodeChunk> chunks = dispatcher.chunk("Broken.java", broken);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allMatch(c -> c.symbolKind() == CodeChunk.SymbolKind.TEXT);
    }

    @Test
    void defaultInstanceFactoryProducesUsableDispatcher() {
        CodeChunkerDispatcher fromFactory = CodeChunkerDispatcher.defaultInstance();
        assertThat(fromFactory.detect("foo.java")).isEqualTo(Language.JAVA);
        assertThat(fromFactory.chunk("main", "hello world")).hasSize(1);
    }
}
