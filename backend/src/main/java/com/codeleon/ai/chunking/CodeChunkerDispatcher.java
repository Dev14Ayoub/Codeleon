package com.codeleon.ai.chunking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Routes a file to the right language chunker, with two layers of safety:
 * an empty AST output (parser succeeded but emitted nothing) falls back to
 * the text chunker, and an exception in a language chunker also falls back —
 * indexing a 200-file project must never abort because one source has a
 * parse error.
 *
 * <p>Holds one instance per language because the per-language chunkers are
 * stateless and cheap; building them eagerly keeps the indexing hot path
 * allocation-free.
 */
@Component
public class CodeChunkerDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CodeChunkerDispatcher.class);

    private final Map<Language, CodeChunker> byLanguage;
    private final CodeChunker fallback;

    public CodeChunkerDispatcher() {
        this.fallback = new FallbackTextChunker();
        Map<Language, CodeChunker> map = new EnumMap<>(Language.class);
        map.put(Language.JAVA, new JavaCodeChunker());
        map.put(Language.JAVASCRIPT, new RegexCodeChunker(Language.JAVASCRIPT));
        map.put(Language.TYPESCRIPT, new RegexCodeChunker(Language.TYPESCRIPT));
        map.put(Language.PYTHON, new RegexCodeChunker(Language.PYTHON));
        this.byLanguage = Map.copyOf(map);
    }

    /**
     * Default-constructed instance for the rare callers (legacy unit tests)
     * that instantiate {@code RoomFileIndexer} without going through Spring
     * DI. Production wiring always uses the singleton bean instead.
     */
    public static CodeChunkerDispatcher defaultInstance() {
        return new CodeChunkerDispatcher();
    }

    public Language detect(String path) {
        return LanguageDetector.detect(path);
    }

    public List<CodeChunk> chunk(String path, String text) {
        if (text == null || text.isBlank()) return List.of();
        Language lang = LanguageDetector.detect(path);
        CodeChunker chunker = byLanguage.getOrDefault(lang, fallback);
        try {
            List<CodeChunk> chunks = chunker.chunk(text);
            if (chunks.isEmpty()) {
                // Parser ran but emitted nothing — degrade to text chunking
                // so the file is still searchable.
                return fallback.chunk(text);
            }
            return chunks;
        } catch (RuntimeException ex) {
            log.warn("Chunker for {} ({}) failed: {} — falling back to text chunker",
                    path, lang, ex.getMessage());
            return fallback.chunk(text);
        }
    }
}
