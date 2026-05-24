package com.codeleon.ai.chunking;

/**
 * Detects a file's language from its path. Pure function — kept off the
 * dispatcher so it can be unit-tested in isolation and shared by anyone
 * who needs a quick language hint without pulling the chunker graph.
 */
public final class LanguageDetector {

    private LanguageDetector() {
    }

    public static Language detect(String path) {
        if (path == null || path.isBlank()) return Language.UNKNOWN;
        String lower = path.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) return Language.UNKNOWN;
        String ext = lower.substring(dot + 1);
        return switch (ext) {
            case "java" -> Language.JAVA;
            case "js", "mjs", "cjs", "jsx" -> Language.JAVASCRIPT;
            case "ts", "tsx" -> Language.TYPESCRIPT;
            case "py", "pyw" -> Language.PYTHON;
            default -> Language.UNKNOWN;
        };
    }
}
