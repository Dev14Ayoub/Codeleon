package com.codeleon.ai.chunking;

import java.util.List;

/**
 * Splits a source file into semantic chunks. Implementations are language-aware:
 * a Java chunker emits one chunk per method/class, a Python chunker emits one per
 * function, and the text fallback walks a sliding window. Implementations must
 * never throw on malformed input — return at most a text fallback instead — so a
 * single corrupt file cannot poison the room indexing run.
 */
public interface CodeChunker {

    /** Hard upper bound on a single chunk's character length. Symbols larger than
     *  this are sub-split by the chunker; nothing reaches the embedder beyond it. */
    int MAX_CHUNK_CHARS = 1_500;

    /** Symbols below this size are merged into the next chunk to avoid embedding
     *  noise from one-line getters or import-only stubs. */
    int MIN_CHUNK_CHARS = 100;

    List<CodeChunk> chunk(String text);
}
