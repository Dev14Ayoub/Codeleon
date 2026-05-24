package com.codeleon.ai.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic, regex-driven chunker for languages we do not pull a full
 * parser for: JavaScript, TypeScript, Python. We scan for declarations
 * at the start of a line (after optional indentation/decorators), then
 * cut a chunk from each declaration to the line before the next one.
 *
 * <p>It is not a parser — it will misattribute symbols inside string
 * literals or templated code — but it is robust enough that the chunks
 * line up with class/function boundaries the vast majority of the time,
 * which is what RAG retrieval actually cares about.
 */
public final class RegexCodeChunker implements CodeChunker {

    // JS / TS: function foo(...), class Foo, const foo = (...) => / function (
    // The leading anchor "(?m)^[ \t]*(?:export\s+)?(?:default\s+)?(?:async\s+)?"
    // lets us match top-level OR exported declarations without false matches
    // inside method bodies.
    private static final Pattern JS_DECL = Pattern.compile(
            "(?m)^[ \\t]*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?" +
                    "(?:" +
                    "(?:function\\*?\\s+([A-Za-z_$][\\w$]*))" +
                    "|(?:class\\s+([A-Za-z_$][\\w$]*))" +
                    "|(?:(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*=>)" +
                    "|(?:(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s+)?function)" +
                    "|(?:interface\\s+([A-Za-z_$][\\w$]*))" +
                    "|(?:type\\s+([A-Za-z_$][\\w$]*)\\s*=)" +
                    "|(?:enum\\s+([A-Za-z_$][\\w$]*))" +
                    ")"
    );

    // Python: def name(...), async def name(...), class Name(...)
    private static final Pattern PY_DECL = Pattern.compile(
            "(?m)^(?:[ \\t]*)(?:async\\s+)?(?:" +
                    "(?:def\\s+([A-Za-z_][\\w]*))" +
                    "|(?:class\\s+([A-Za-z_][\\w]*))" +
                    ")"
    );

    private final Language language;

    public RegexCodeChunker(Language language) {
        if (language != Language.JAVASCRIPT && language != Language.TYPESCRIPT && language != Language.PYTHON) {
            throw new IllegalArgumentException("RegexCodeChunker does not support " + language);
        }
        this.language = language;
    }

    @Override
    public List<CodeChunk> chunk(String text) {
        if (text == null || text.isBlank()) return List.of();

        Pattern pattern = (language == Language.PYTHON) ? PY_DECL : JS_DECL;
        Matcher m = pattern.matcher(text);

        // First pass: collect every match (offset + symbol name + kind).
        List<Match> matches = new ArrayList<>();
        while (m.find()) {
            String name = firstNonNullGroup(m);
            if (name == null) continue;
            CodeChunk.SymbolKind kind = kindOf(m, name);
            matches.add(new Match(m.start(), name, kind));
        }

        if (matches.isEmpty()) {
            // No declarations recognised — let the dispatcher fall back to
            // text chunking. Returning an empty list signals that intent.
            return List.of();
        }

        // Second pass: slice from match[i].start to match[i+1].start.
        List<CodeChunk> chunks = new ArrayList<>(matches.size() + 1);

        // Pre-header (everything above the first decl) only if it carries
        // useful content like imports — checked by min-size threshold.
        int firstStart = matches.get(0).offset;
        if (firstStart > 0) {
            String header = text.substring(0, firstStart).stripTrailing();
            if (header.length() >= MIN_CHUNK_CHARS) {
                int endLine = lineAt(text, firstStart - 1);
                chunks.add(new CodeChunk(header, "imports", CodeChunk.SymbolKind.BLOCK, 1, Math.max(1, endLine)));
            }
        }

        for (int i = 0; i < matches.size(); i++) {
            Match cur = matches.get(i);
            int sliceEnd = (i + 1 < matches.size()) ? matches.get(i + 1).offset : text.length();
            String body = text.substring(cur.offset, sliceEnd).stripTrailing();
            if (body.isBlank()) continue;
            int startLine = lineAt(text, cur.offset);
            int endLine = lineAt(text, Math.max(cur.offset, sliceEnd - 1));

            if (body.length() <= MAX_CHUNK_CHARS) {
                // Merge a tiny chunk into the previous one only when both
                // share a SymbolKind — a CLASS should never be swallowed by
                // a FUNCTION, an INTERFACE never absorbed by a TYPE. This
                // keeps symbol attribution honest even when declarations
                // are stacked back-to-back with no body code between them.
                if (body.length() < MIN_CHUNK_CHARS && !chunks.isEmpty()) {
                    CodeChunk last = chunks.get(chunks.size() - 1);
                    if (last.symbolKind() == cur.kind
                            && last.text().length() + body.length() + 2 <= MAX_CHUNK_CHARS) {
                        chunks.set(chunks.size() - 1, new CodeChunk(
                                last.text() + "\n\n" + body,
                                last.symbol(),
                                last.symbolKind(),
                                last.startLine(),
                                endLine
                        ));
                        continue;
                    }
                }
                chunks.add(new CodeChunk(body, cur.name, cur.kind, startLine, endLine));
            } else {
                splitOversized(body, cur.name, cur.kind, startLine, chunks);
            }
        }

        return chunks;
    }

    private static void splitOversized(String body, String symbol, CodeChunk.SymbolKind kind,
                                       int startLine, List<CodeChunk> out) {
        String[] lines = body.split("\n", -1);
        StringBuilder buf = new StringBuilder();
        int chunkStart = startLine;
        int lineNum = startLine;
        for (String line : lines) {
            if (buf.length() + line.length() + 1 > MAX_CHUNK_CHARS && buf.length() > 0) {
                out.add(new CodeChunk(buf.toString().stripTrailing(), symbol, kind, chunkStart, lineNum - 1));
                buf.setLength(0);
                chunkStart = lineNum;
            }
            buf.append(line).append('\n');
            lineNum++;
        }
        if (buf.length() > 0) {
            out.add(new CodeChunk(buf.toString().stripTrailing(), symbol, kind, chunkStart, lineNum - 1));
        }
    }

    private CodeChunk.SymbolKind kindOf(Matcher m, String name) {
        // Group ordering mirrors the regex above. JS groups 1..7, Python 1..2.
        if (language == Language.PYTHON) {
            if (m.group(1) != null) return CodeChunk.SymbolKind.FUNCTION;
            if (m.group(2) != null) return CodeChunk.SymbolKind.CLASS;
        } else {
            if (m.group(1) != null) return CodeChunk.SymbolKind.FUNCTION; // function foo
            if (m.group(2) != null) return CodeChunk.SymbolKind.CLASS;    // class Foo
            if (m.group(3) != null) return CodeChunk.SymbolKind.FUNCTION; // const foo = () =>
            if (m.group(4) != null) return CodeChunk.SymbolKind.FUNCTION; // const foo = function
            if (m.group(5) != null) return CodeChunk.SymbolKind.INTERFACE;
            if (m.group(6) != null) return CodeChunk.SymbolKind.BLOCK;    // type alias
            if (m.group(7) != null) return CodeChunk.SymbolKind.ENUM;
        }
        return CodeChunk.SymbolKind.BLOCK;
    }

    private static String firstNonNullGroup(Matcher m) {
        for (int i = 1; i <= m.groupCount(); i++) {
            String g = m.group(i);
            if (g != null) return g;
        }
        return null;
    }

    private static int lineAt(String text, int offset) {
        int line = 1;
        int bound = Math.min(offset, text.length());
        for (int i = 0; i < bound; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    private record Match(int offset, String name, CodeChunk.SymbolKind kind) {}
}
