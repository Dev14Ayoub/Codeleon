package com.codeleon.ai.chunking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegexCodeChunkerTest {

    @Test
    void rejectsUnsupportedLanguage() {
        assertThatThrownBy(() -> new RegexCodeChunker(Language.JAVA))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyInputProducesNoChunks() {
        RegexCodeChunker chunker = new RegexCodeChunker(Language.JAVASCRIPT);
        assertThat(chunker.chunk(null)).isEmpty();
        assertThat(chunker.chunk("   ")).isEmpty();
    }

    @Test
    void detectsJsFunctionAndClass() {
        String source = """
                import { x } from './x';
                import { y } from './y';

                export function greet(name) {
                    // pad the body so it clears the merge threshold
                    console.log('hello ' + name);
                    console.log('still hello');
                    return name.toUpperCase();
                }

                export class Widget {
                    constructor(label) {
                        this.label = label;
                    }

                    render() {
                        return this.label;
                    }
                }
                """;
        List<CodeChunk> chunks = new RegexCodeChunker(Language.JAVASCRIPT).chunk(source);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).anyMatch(c -> "greet".equals(c.symbol())
                && c.symbolKind() == CodeChunk.SymbolKind.FUNCTION);
        assertThat(chunks).anyMatch(c -> "Widget".equals(c.symbol())
                && c.symbolKind() == CodeChunk.SymbolKind.CLASS);
    }

    @Test
    void detectsArrowAssignmentInJs() {
        String source = """
                const fetchUser = async (id) => {
                    const response = await fetch(`/users/${id}`);
                    if (!response.ok) throw new Error('failed');
                    return response.json();
                };
                """;
        List<CodeChunk> chunks = new RegexCodeChunker(Language.JAVASCRIPT).chunk(source);
        assertThat(chunks).anyMatch(c -> "fetchUser".equals(c.symbol())
                && c.symbolKind() == CodeChunk.SymbolKind.FUNCTION);
    }

    @Test
    void detectsTsInterfaceAndType() {
        String source = """
                export interface User {
                    id: string;
                    email: string;
                    role: 'admin' | 'member';
                }

                export type UserList = User[];
                """;
        List<CodeChunk> chunks = new RegexCodeChunker(Language.TYPESCRIPT).chunk(source);
        assertThat(chunks).anyMatch(c -> "User".equals(c.symbol()));
    }

    @Test
    void detectsPythonDefAndClass() {
        String source = """
                import os
                import sys

                def fibonacci(n):
                    if n <= 1:
                        return n
                    return fibonacci(n - 1) + fibonacci(n - 2)

                class Calculator:
                    def __init__(self):
                        self.history = []

                    def add(self, a, b):
                        result = a + b
                        self.history.append(result)
                        return result
                """;
        List<CodeChunk> chunks = new RegexCodeChunker(Language.PYTHON).chunk(source);
        assertThat(chunks).anyMatch(c -> "fibonacci".equals(c.symbol())
                && c.symbolKind() == CodeChunk.SymbolKind.FUNCTION);
        assertThat(chunks).anyMatch(c -> "Calculator".equals(c.symbol())
                && c.symbolKind() == CodeChunk.SymbolKind.CLASS);
    }

    @Test
    void returnsEmptyWhenNoDeclarationsFound() {
        // A bare data file with no functions or classes — the dispatcher
        // will see the empty list and fall back to text chunking.
        String source = "const x = 1;\nconst y = 2;\nconst z = 3;\n";
        List<CodeChunk> chunks = new RegexCodeChunker(Language.JAVASCRIPT).chunk(source);
        assertThat(chunks).isEmpty();
    }
}
