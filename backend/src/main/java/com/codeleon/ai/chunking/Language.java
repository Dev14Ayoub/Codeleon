package com.codeleon.ai.chunking;

/**
 * Source languages the chunker can recognise. {@link #UNKNOWN} forces the
 * fallback text chunker — it is the safe default when no extension matches.
 */
public enum Language {
    JAVA,
    JAVASCRIPT,
    TYPESCRIPT,
    PYTHON,
    UNKNOWN
}
