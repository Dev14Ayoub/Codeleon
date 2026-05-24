package com.codeleon.ai.agent.tools;

import com.codeleon.ai.RoomFileSnapshotStore;
import com.codeleon.ai.agent.AgentTool;
import com.codeleon.ai.agent.ToolExecutionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Proposes a targeted edit to a file. The agent expresses the edit as a
 * find/replace pair — concrete and grep-able — rather than a free-form
 * diff that would be hard to apply safely. Validation runs on the
 * indexed snapshot: {@code find} must appear exactly once in the file,
 * which prevents both "no-op" patches (find missing) and ambiguous
 * patches (multiple matches) before the user ever sees them.
 *
 * <p>The backend never modifies the room's Y.Doc — server-side Yjs is
 * not available. Instead the tool returns a JSON payload that the chat
 * frontend recognises and renders as a "Apply patch?" card. When the
 * user confirms, the frontend writes the change to the bound Y.Text;
 * the CRDT layer propagates to collaborators.
 */
@Component
@RequiredArgsConstructor
public class ProposePatchTool implements AgentTool {

    private final RoomFileSnapshotStore snapshots;
    private final ObjectMapper mapper;

    /** Monotonic patch-id source — purely for UI correlation, never persisted. */
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public String name() {
        return "propose_patch";
    }

    @Override
    public String description() {
        return "Propose a precise edit to a file. The patch is shown to the user with Apply / Reject "
                + "buttons — you never apply changes directly. Use AFTER reading the relevant code with "
                + "read_file. 'find' must be a unique substring of the file (will be rejected if it appears "
                + "zero or more than once). Keep 'find' tight — one statement or block, not the whole file.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "Exact file path (as returned by list_files)."
                        ),
                        "find", Map.of(
                                "type", "string",
                                "description", "Exact text to replace. Must appear exactly once in the file."
                        ),
                        "replace", Map.of(
                                "type", "string",
                                "description", "Replacement text. Can be empty to delete the matched region."
                        ),
                        "rationale", Map.of(
                                "type", "string",
                                "description", "Short explanation shown to the user above the diff."
                        )
                ),
                "required", List.of("path", "find", "replace")
        );
    }

    @Override
    public String execute(UUID roomId, Map<String, Object> arguments) throws ToolExecutionException {
        String path = requireString(arguments, "path");
        String find = requireString(arguments, "find");
        // replace can legitimately be the empty string (= delete), so we
        // accept null/missing → "" rather than rejecting.
        String replace = arguments.get("replace") == null ? "" : arguments.get("replace").toString();
        String rationale = arguments.get("rationale") == null ? "" : arguments.get("rationale").toString();

        String fileText = snapshots.get(roomId, path);
        if (fileText == null) {
            throw new ToolExecutionException("File '" + path + "' is not indexed. "
                    + "Run list_files first, then read_file to see its content.");
        }

        int first = fileText.indexOf(find);
        if (first < 0) {
            throw new ToolExecutionException("The 'find' text was not found in '" + path + "'. "
                    + "Re-read the file (its content may have changed) and quote the exact text.");
        }
        int last = fileText.lastIndexOf(find);
        if (first != last) {
            throw new ToolExecutionException("The 'find' text appears multiple times in '" + path + "'. "
                    + "Make 'find' more specific so it is unique — include surrounding context.");
        }

        String patchId = "patch_" + sequence.incrementAndGet();

        // The payload is JSON so the frontend can parse it deterministically.
        // We return it as the tool's text content rather than via a side
        // channel — the agent loop's protocol only carries strings.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "patch_proposal");
        payload.put("patchId", patchId);
        payload.put("path", path);
        payload.put("find", find);
        payload.put("replace", replace);
        payload.put("rationale", rationale);

        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new ToolExecutionException("Failed to encode patch payload: " + ex.getMessage(), ex);
        }
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolExecutionException {
        Object raw = args.get(key);
        if (raw == null || raw.toString().isBlank()) {
            throw new ToolExecutionException("Missing required argument '" + key + "'");
        }
        return raw.toString();
    }
}
