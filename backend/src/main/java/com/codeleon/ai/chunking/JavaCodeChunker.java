package com.codeleon.ai.chunking;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a Java source file into one chunk per top-level/nested class member
 * (methods, constructors, fields) and one summary chunk per class declaration
 * (its imports + signature). The chunker walks the AST top-down and emits
 * symbols using their dotted path — e.g. {@code AuthService.refreshToken}
 * for a method, {@code AuthService.LOG} for a field.
 *
 * <p>If the source fails to parse (broken syntax mid-edit), an empty list is
 * returned so the dispatcher can fall back to text chunking. We do not log
 * the parse errors here — they are normal during live editing.
 */
public final class JavaCodeChunker implements CodeChunker {

    /**
     * Parser configuration is immutable and reusable across threads; the
     * {@link JavaParser} instance built from it is NOT — it keeps internal
     * problem-reporter state during {@code parse}, so two concurrent
     * indexings sharing one parser would race on that state.
     *
     * <p>{@link CodeChunkerDispatcher} holds a single {@code JavaCodeChunker}
     * as a Spring singleton, and {@code RoomFileIndexer.index} can be
     * invoked from many HTTP threads (one per concurrent chat / index
     * request), so the field-level parser was a real race. We now build
     * a fresh parser per call — the cost is microseconds compared to the
     * parse itself, and the safety is unconditional.
     */
    private static final ParserConfiguration PARSER_CONFIG = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    public JavaCodeChunker() {
    }

    @Override
    public List<CodeChunk> chunk(String text) {
        if (text == null || text.isBlank()) return List.of();

        ParseResult<CompilationUnit> result = new JavaParser(PARSER_CONFIG).parse(text);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return List.of();
        }
        CompilationUnit cu = result.getResult().get();

        // Header chunk: package + imports. Lets the assistant find a file
        // by its imports/package even when no specific symbol matches.
        List<CodeChunk> chunks = new ArrayList<>();
        String header = buildHeader(cu, text);
        if (header != null && header.length() >= MIN_CHUNK_CHARS) {
            int headerEndLine = cu.getImports().isEmpty()
                    ? cu.getRange().map(r -> r.begin.line).orElse(1)
                    : cu.getImports().get(cu.getImports().size() - 1)
                            .getRange().map(r -> r.end.line).orElse(1);
            chunks.add(new CodeChunk(header, "imports", CodeChunk.SymbolKind.BLOCK, 1, headerEndLine));
        }

        for (TypeDeclaration<?> type : cu.getTypes()) {
            emitType(type, "", text, chunks);
        }

        // If a class is empty (no members), still emit the class signature
        // alone so the file has at least one symbol-attributed chunk.
        if (chunks.isEmpty()) {
            for (TypeDeclaration<?> type : cu.getTypes()) {
                String body = sliceByRange(text, type.getRange().orElse(null));
                if (body != null && !body.isBlank()) {
                    Range r = type.getRange().orElse(null);
                    chunks.add(new CodeChunk(body, typeName(type), kindOf(type),
                            r != null ? r.begin.line : 1,
                            r != null ? r.end.line : 1));
                }
            }
        }
        return chunks;
    }

    private void emitType(TypeDeclaration<?> type, String parentPath, String source, List<CodeChunk> out) {
        String path = parentPath.isEmpty() ? typeName(type) : parentPath + "." + typeName(type);
        Range typeRange = type.getRange().orElse(null);

        // Class signature chunk: declaration line through opening brace.
        if (typeRange != null) {
            String signature = sliceSignature(type, source);
            if (signature != null && signature.length() >= MIN_CHUNK_CHARS) {
                int sigEndLine = type.getMembers().isEmpty()
                        ? typeRange.end.line
                        : type.getMembers().get(0).getRange().map(r -> r.begin.line - 1).orElse(typeRange.begin.line);
                out.add(new CodeChunk(signature, path, kindOf(type), typeRange.begin.line, Math.max(sigEndLine, typeRange.begin.line)));
            }
        }

        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof MethodDeclaration m) {
                emitMember(m.getNameAsString(), CodeChunk.SymbolKind.METHOD, m.getRange().orElse(null), source, path, out);
            } else if (member instanceof ConstructorDeclaration c) {
                emitMember(c.getNameAsString(), CodeChunk.SymbolKind.CONSTRUCTOR, c.getRange().orElse(null), source, path, out);
            } else if (member instanceof FieldDeclaration f) {
                String name = f.getVariables().stream()
                        .map(VariableDeclarator::getNameAsString)
                        .findFirst()
                        .orElse("field");
                emitMember(name, CodeChunk.SymbolKind.FIELD, f.getRange().orElse(null), source, path, out);
            } else if (member instanceof TypeDeclaration<?> nested) {
                emitType(nested, path, source, out);
            }
        }
    }

    private void emitMember(String name, CodeChunk.SymbolKind kind, Range range, String source,
                            String parentPath, List<CodeChunk> out) {
        if (range == null) return;
        String text = sliceByRange(source, range);
        if (text == null || text.isBlank()) return;

        String symbol = parentPath + "." + name;

        if (text.length() <= MAX_CHUNK_CHARS) {
            // Tiny field/getter — merge into the previous chunk only when it
            // is the same SymbolKind. We do not let a method swallow a class
            // signature (or vice versa) just because one of them is short;
            // RAG attribution needs each declaration to stay searchable on
            // its own.
            if (text.length() < MIN_CHUNK_CHARS && !out.isEmpty()) {
                CodeChunk last = out.get(out.size() - 1);
                if (last.symbolKind() == kind
                        && last.text().length() + text.length() + 2 <= MAX_CHUNK_CHARS) {
                    out.set(out.size() - 1, new CodeChunk(
                            last.text() + "\n\n" + text,
                            last.symbol(),
                            last.symbolKind(),
                            last.startLine(),
                            range.end.line
                    ));
                    return;
                }
            }
            out.add(new CodeChunk(text, symbol, kind, range.begin.line, range.end.line));
            return;
        }

        // Method larger than the budget — split by lines so we never emit a
        // chunk past MAX_CHUNK_CHARS, and tag each piece with the same symbol
        // so retrieval can still attribute them.
        splitOversized(text, symbol, kind, range.begin, out);
    }

    private void splitOversized(String text, String symbol, CodeChunk.SymbolKind kind,
                                 Position startPos, List<CodeChunk> out) {
        String[] lines = text.split("\n", -1);
        StringBuilder buf = new StringBuilder();
        int chunkStartLine = startPos.line;
        int lineNum = startPos.line;
        for (String line : lines) {
            if (buf.length() + line.length() + 1 > MAX_CHUNK_CHARS && buf.length() > 0) {
                out.add(new CodeChunk(buf.toString(), symbol, kind, chunkStartLine, lineNum - 1));
                buf.setLength(0);
                chunkStartLine = lineNum;
            }
            buf.append(line).append('\n');
            lineNum++;
        }
        if (buf.length() > 0) {
            out.add(new CodeChunk(buf.toString().stripTrailing(), symbol, kind, chunkStartLine, lineNum - 1));
        }
    }

    private static String buildHeader(CompilationUnit cu, String source) {
        StringBuilder sb = new StringBuilder();
        cu.getPackageDeclaration().ifPresent(p ->
                sb.append("package ").append(p.getNameAsString()).append(";\n\n"));
        cu.getImports().forEach(imp -> sb.append(imp.toString()));
        return sb.toString();
    }

    private static String sliceSignature(TypeDeclaration<?> type, String source) {
        Range r = type.getRange().orElse(null);
        if (r == null) return null;
        // Signature is everything from the type's begin to either the first
        // member's begin or the type's own end if there are no members.
        Position end = type.getMembers().isEmpty()
                ? r.end
                : type.getMembers().get(0).getRange().map(mr -> mr.begin).orElse(r.end);
        return sliceByPositions(source, r.begin, end);
    }

    private static String sliceByRange(String source, Range range) {
        if (range == null) return null;
        return sliceByPositions(source, range.begin, range.end);
    }

    private static String sliceByPositions(String source, Position begin, Position end) {
        int start = offsetOf(source, begin);
        int stop = offsetOf(source, end);
        if (start < 0 || stop < 0 || stop <= start) return null;
        return source.substring(start, Math.min(stop, source.length()));
    }

    /** Converts a 1-indexed line/column into an absolute char offset. */
    private static int offsetOf(String source, Position pos) {
        if (pos == null) return -1;
        int line = 1;
        int col = 1;
        for (int i = 0; i < source.length(); i++) {
            if (line == pos.line && col == pos.column) return i;
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return source.length();
    }

    private static String typeName(TypeDeclaration<?> type) {
        return type.getNameAsString();
    }

    private static CodeChunk.SymbolKind kindOf(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration c) {
            return c.isInterface() ? CodeChunk.SymbolKind.INTERFACE : CodeChunk.SymbolKind.CLASS;
        }
        if (type instanceof EnumDeclaration) return CodeChunk.SymbolKind.ENUM;
        if (type instanceof RecordDeclaration) return CodeChunk.SymbolKind.RECORD;
        return CodeChunk.SymbolKind.CLASS;
    }
}
