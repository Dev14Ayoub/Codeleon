package com.codeleon.ai.retrieval;

import com.codeleon.ai.chunking.CodeChunk;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory BM25 index, one Lucene directory per room. Lives next to the
 * Qdrant vector store: same write events feed both, search-time RRF merges
 * their rankings.
 *
 * <p>Memory cost is bounded by chunk count and chunk size — a room with
 * 200 files × 5 chunks × 1 KB is roughly a megabyte of Lucene state. On a
 * single-process backend this is fine; if Codeleon ever scales out, this
 * would need to move to a shared store (OpenSearch, Postgres FTS).
 *
 * <p>Thread-safety: one {@link IndexWriter} per room, synchronised on the
 * room state object so concurrent indexers in different rooms never block
 * each other but two indexers in the same room serialise.
 */
@Component
public class Bm25Searcher {

    private static final Logger log = LoggerFactory.getLogger(Bm25Searcher.class);

    private static final String FIELD_ID = "id";
    private static final String FIELD_PATH = "path";
    private static final String FIELD_SYMBOL = "symbol";
    private static final String FIELD_SYMBOL_RAW = "symbolRaw";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_TEXT_RAW = "textRaw";
    private static final String FIELD_CHUNK_INDEX = "chunkIndex";
    private static final String FIELD_START_LINE = "startLine";
    private static final String FIELD_END_LINE = "endLine";
    private static final String FIELD_SYMBOL_KIND = "symbolKind";

    /**
     * Code punctuation that StandardAnalyzer would otherwise keep glued to
     * identifiers (UAX#29 treats {@code System.out.println} as one token).
     * We rewrite these to spaces at both index and query time so an exact
     * identifier search like {@code println} hits the chunk that calls it.
     */
    private static final java.util.regex.Pattern CODE_PUNCTUATION =
            java.util.regex.Pattern.compile("[.()\\[\\]{},;:\"'<>/=+\\-*&|!?@#$%^~`\\\\]");

    private static String tokenize(String text) {
        if (text == null) return "";
        return CODE_PUNCTUATION.matcher(text).replaceAll(" ");
    }

    private final ConcurrentHashMap<UUID, RoomState> rooms = new ConcurrentHashMap<>();

    /**
     * Replaces all chunks for a given path in the room's index, preserving
     * the existing chunks for other paths. Matches {@code RoomFileIndexer}'s
     * upsert semantics so the BM25 view and the Qdrant view stay aligned.
     */
    public void upsertFile(UUID roomId, String path, List<CodeChunk> chunks) {
        RoomState state = rooms.computeIfAbsent(roomId, this::createState);
        synchronized (state) {
            // Re-check the closed flag inside the lock — a concurrent
            // deleteRoom may have already torn down this state's writer
            // between computeIfAbsent and our acquiring the monitor.
            if (state.closed.get()) {
                log.debug("Skipping BM25 upsert for room {} path {}: room was just deleted", roomId, path);
                return;
            }
            try {
                state.writer.deleteDocuments(new Term(FIELD_PATH, path));
                for (int i = 0; i < chunks.size(); i++) {
                    CodeChunk c = chunks.get(i);
                    Document doc = new Document();
                    doc.add(new StringField(FIELD_ID, path + ":" + i, Field.Store.NO));
                    doc.add(new StringField(FIELD_PATH, path, Field.Store.YES));
                    if (c.symbol() != null) {
                        // Two complementary representations:
                        //  - FIELD_SYMBOL: tokenised (un-stored) for matching —
                        //    "AuthService.refreshToken" indexes as both tokens.
                        //  - FIELD_SYMBOL_RAW: stored verbatim for display, so
                        //    callers see the original dotted symbol path.
                        doc.add(new TextField(FIELD_SYMBOL, tokenize(c.symbol()), Field.Store.NO));
                        doc.add(new StoredField(FIELD_SYMBOL_RAW, c.symbol()));
                    }
                    if (c.symbolKind() != null) {
                        doc.add(new StoredField(FIELD_SYMBOL_KIND, c.symbolKind().name()));
                    }
                    doc.add(new TextField(FIELD_TEXT, tokenize(c.text()), Field.Store.NO));
                    doc.add(new StoredField(FIELD_TEXT_RAW, c.text()));
                    doc.add(new StoredField(FIELD_CHUNK_INDEX, i));
                    doc.add(new StoredField(FIELD_START_LINE, c.startLine()));
                    doc.add(new StoredField(FIELD_END_LINE, c.endLine()));
                    state.writer.addDocument(doc);
                }
                state.writer.commit();
            } catch (IOException ex) {
                throw new IllegalStateException("BM25 upsert failed for room " + roomId + " path " + path, ex);
            }
        }
    }

    public void deletePath(UUID roomId, String path) {
        RoomState state = rooms.get(roomId);
        if (state == null) return;
        synchronized (state) {
            if (state.closed.get()) return;
            try {
                state.writer.deleteDocuments(new Term(FIELD_PATH, path));
                state.writer.commit();
            } catch (IOException ex) {
                throw new IllegalStateException("BM25 delete failed for room " + roomId + " path " + path, ex);
            }
        }
    }

    public void deleteRoom(UUID roomId) {
        RoomState state = rooms.remove(roomId);
        if (state == null) return;
        synchronized (state) {
            // Flag first so any thread waiting on this monitor (e.g. a
            // concurrent search that captured the state ref before our
            // rooms.remove call) bails out instead of touching the
            // closed writer.
            state.closed.set(true);
            closeQuietly(state);
        }
    }

    public List<Hit> search(UUID roomId, String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) return List.of();
        RoomState state = rooms.get(roomId);
        if (state == null) return List.of();

        synchronized (state) {
            if (state.closed.get()) return List.of();
            try (DirectoryReader reader = DirectoryReader.open(state.writer)) {
                if (reader.numDocs() == 0) return List.of();
                IndexSearcher searcher = new IndexSearcher(reader);
                searcher.setSimilarity(new BM25Similarity());

                Query parsed = buildQuery(query, state.analyzer);
                TopDocs hits = searcher.search(parsed, Math.max(topK, 1));

                List<Hit> out = new ArrayList<>(hits.scoreDocs.length);
                for (ScoreDoc sd : hits.scoreDocs) {
                    Document doc = searcher.storedFields().document(sd.doc);
                    out.add(new Hit(
                            doc.get(FIELD_PATH),
                            doc.get(FIELD_SYMBOL_RAW),
                            doc.get(FIELD_SYMBOL_KIND),
                            optInt(doc.get(FIELD_START_LINE)),
                            optInt(doc.get(FIELD_END_LINE)),
                            optInt(doc.get(FIELD_CHUNK_INDEX), 0),
                            doc.get(FIELD_TEXT_RAW),
                            sd.score
                    ));
                }
                return out;
            } catch (IOException ex) {
                log.warn("BM25 search for room {} failed: {}", roomId, ex.getMessage());
                return List.of();
            }
        }
    }

    /**
     * Parses the user's query as a Lucene expression over the indexed text,
     * with a should-clause on the symbol field so an exact identifier match
     * (e.g. {@code refreshToken}) wins the ranking even when the symbol is
     * not used in the chunk body. We escape the input first — users do not
     * write Lucene syntax, and an unescaped {@code (} or {@code :} explodes
     * the parser.
     */
    private static Query buildQuery(String userQuery, StandardAnalyzer analyzer) {
        // Tokenise the query the same way we tokenised the indexed text —
        // otherwise "println(" never matches "println" because UAX#29
        // keeps trailing punctuation in the term, and "User.refreshToken"
        // never matches a chunk that calls refreshToken on `auth`.
        String escaped = QueryParser.escape(tokenize(userQuery));
        try {
            QueryParser textParser = new QueryParser(FIELD_TEXT, analyzer);
            Query textQuery = textParser.parse(escaped);

            QueryParser symbolParser = new QueryParser(FIELD_SYMBOL, analyzer);
            Query symbolQuery = symbolParser.parse(escaped);

            return new BooleanQuery.Builder()
                    .add(textQuery, BooleanClause.Occur.SHOULD)
                    .add(symbolQuery, BooleanClause.Occur.SHOULD)
                    .build();
        } catch (Exception ex) {
            // Final safety net — even with escaping a pathological query
            // could fail; fall back to a term query so we always return
            // *something* rather than throwing into the chat pipeline.
            // Locale.ROOT guarantees the lowercasing matches what
            // StandardAnalyzer applies at index time, regardless of the
            // server's locale (Turkish 'I' → 'ı' would not match).
            return new org.apache.lucene.search.TermQuery(new Term(FIELD_TEXT, userQuery.toLowerCase(Locale.ROOT)));
        }
    }

    private RoomState createState(UUID roomId) {
        StandardAnalyzer analyzer = new StandardAnalyzer();
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        IndexWriterConfig cfg = new IndexWriterConfig(analyzer)
                .setSimilarity(new BM25Similarity())
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        try {
            IndexWriter writer = new IndexWriter(dir, cfg);
            return new RoomState(analyzer, dir, writer);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to open BM25 index for room " + roomId, ex);
        }
    }

    @PreDestroy
    public void shutdown() {
        rooms.values().forEach(this::closeQuietly);
        rooms.clear();
    }

    private void closeQuietly(RoomState state) {
        try { state.writer.close(); } catch (IOException ignored) {}
        try { state.directory.close(); } catch (IOException ignored) {}
        state.analyzer.close();
    }

    private static Integer optInt(String value) {
        if (value == null) return null;
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return null; }
    }

    private static int optInt(String value, int fallback) {
        Integer parsed = optInt(value);
        return parsed == null ? fallback : parsed;
    }

    public record Hit(
            String path,
            String symbol,
            String symbolKind,
            Integer startLine,
            Integer endLine,
            int chunkIndex,
            String text,
            double score
    ) {}

    /**
     * Per-room mutable state. Holds the Lucene plumbing plus a {@code closed}
     * flag that {@link #deleteRoom} flips inside the lock so any concurrent
     * search or upsert that still has a stale reference bails out rather
     * than touching an already-closed writer.
     */
    private record RoomState(
            StandardAnalyzer analyzer,
            ByteBuffersDirectory directory,
            IndexWriter writer,
            AtomicBoolean closed
    ) {
        RoomState(StandardAnalyzer analyzer, ByteBuffersDirectory directory, IndexWriter writer) {
            this(analyzer, directory, writer, new AtomicBoolean(false));
        }
    }
}
