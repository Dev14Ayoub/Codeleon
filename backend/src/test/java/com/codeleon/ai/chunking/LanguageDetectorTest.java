package com.codeleon.ai.chunking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageDetectorTest {

    @Test
    void detectsJavaByExtension() {
        assertThat(LanguageDetector.detect("src/main/java/Foo.java")).isEqualTo(Language.JAVA);
    }

    @Test
    void detectsJsTsAndJsxVariants() {
        assertThat(LanguageDetector.detect("app/index.js")).isEqualTo(Language.JAVASCRIPT);
        assertThat(LanguageDetector.detect("app/index.mjs")).isEqualTo(Language.JAVASCRIPT);
        assertThat(LanguageDetector.detect("app/Component.jsx")).isEqualTo(Language.JAVASCRIPT);
        assertThat(LanguageDetector.detect("app/Component.tsx")).isEqualTo(Language.TYPESCRIPT);
        assertThat(LanguageDetector.detect("src/util.ts")).isEqualTo(Language.TYPESCRIPT);
    }

    @Test
    void detectsPython() {
        assertThat(LanguageDetector.detect("script.py")).isEqualTo(Language.PYTHON);
        assertThat(LanguageDetector.detect("script.pyw")).isEqualTo(Language.PYTHON);
    }

    @Test
    void returnsUnknownForExtensionlessOrMissingPath() {
        // The pre-AST indexer used path="main" as the default — must keep
        // resolving to UNKNOWN so the fallback chunker handles it the same.
        assertThat(LanguageDetector.detect("main")).isEqualTo(Language.UNKNOWN);
        assertThat(LanguageDetector.detect("README")).isEqualTo(Language.UNKNOWN);
        assertThat(LanguageDetector.detect(null)).isEqualTo(Language.UNKNOWN);
        assertThat(LanguageDetector.detect("")).isEqualTo(Language.UNKNOWN);
        assertThat(LanguageDetector.detect("file.")).isEqualTo(Language.UNKNOWN);
    }

    @Test
    void isCaseInsensitive() {
        assertThat(LanguageDetector.detect("Foo.JAVA")).isEqualTo(Language.JAVA);
        assertThat(LanguageDetector.detect("App.TSX")).isEqualTo(Language.TYPESCRIPT);
    }
}
