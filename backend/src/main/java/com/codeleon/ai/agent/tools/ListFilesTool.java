package com.codeleon.ai.agent.tools;

import com.codeleon.ai.RoomFileSnapshotStore;
import com.codeleon.ai.agent.AgentTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lists every file path the AI pipeline has seen for this room. Backed by
 * the snapshot store rather than {@link com.codeleon.room.RoomFileService}
 * so the agent only ever sees files that have actually been indexed —
 * matching what {@code search_code} and {@code semantic_search} can hit.
 */
@Component
@RequiredArgsConstructor
public class ListFilesTool implements AgentTool {

    private final RoomFileSnapshotStore snapshots;

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public String description() {
        return "List every file path in the current project. Returns one path per line. "
                + "Use this to discover the project's layout before searching or reading.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of()
        );
    }

    @Override
    public String execute(UUID roomId, Map<String, Object> arguments) {
        List<String> paths = snapshots.listPaths(roomId);
        if (paths.isEmpty()) {
            return "(no files indexed yet for this room)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(paths.size()).append(" file").append(paths.size() == 1 ? "" : "s").append(":\n");
        for (String path : paths) {
            sb.append(path).append('\n');
        }
        return sb.toString();
    }
}
