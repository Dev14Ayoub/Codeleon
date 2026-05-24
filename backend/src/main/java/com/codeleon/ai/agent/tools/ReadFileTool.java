package com.codeleon.ai.agent.tools;

import com.codeleon.ai.RoomFileSnapshotStore;
import com.codeleon.ai.agent.AgentTool;
import com.codeleon.ai.agent.ToolExecutionException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Returns the indexed content of a file in the room. Use this when the
 * agent needs the full body of a file rather than the per-chunk excerpts
 * the search tools surface. Not a full editor view — only what has been
 * indexed (the frontend re-indexes before every chat turn, so this is
 * effectively current).
 */
@Component
@RequiredArgsConstructor
public class ReadFileTool implements AgentTool {

    private final RoomFileSnapshotStore snapshots;

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read the full text of one file in the project. Returns the file content with "
                + "1-indexed line numbers prepended so you can cite specific lines in your answer.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "Exact file path returned by list_files or search_code (e.g. src/main/java/App.java)."
                        )
                ),
                "required", List.of("path")
        );
    }

    @Override
    public String execute(UUID roomId, Map<String, Object> arguments) throws ToolExecutionException {
        Object rawPath = arguments.get("path");
        if (rawPath == null || rawPath.toString().isBlank()) {
            throw new ToolExecutionException("Missing required argument 'path'");
        }
        String path = rawPath.toString().trim();
        String text = snapshots.get(roomId, path);
        if (text == null) {
            throw new ToolExecutionException("File '" + path + "' is not indexed. "
                    + "Run list_files to see what is available.");
        }
        return numberLines(path, text);
    }

    /**
     * Prefixes every line with its 1-indexed number so the model can cite
     * "L42" in its answer with confidence. Cheap — runs once per read.
     */
    private static String numberLines(String path, String text) {
        String[] lines = text.split("\n", -1);
        int width = String.valueOf(lines.length).length();
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(path).append(" (").append(lines.length).append(" lines) ---\n");
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format("%" + width + "d", i + 1)).append(" | ").append(lines[i]).append('\n');
        }
        return sb.toString();
    }
}
