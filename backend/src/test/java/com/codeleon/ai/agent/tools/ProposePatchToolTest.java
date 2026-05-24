package com.codeleon.ai.agent.tools;

import com.codeleon.ai.RoomFileSnapshotStore;
import com.codeleon.ai.agent.ToolExecutionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProposePatchToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ProposePatchTool toolWith(RoomFileSnapshotStore store) {
        return new ProposePatchTool(store, mapper);
    }

    @Test
    void missingPathArgumentRaisesError() {
        ProposePatchTool tool = toolWith(new RoomFileSnapshotStore());
        assertThatThrownBy(() -> tool.execute(UUID.randomUUID(),
                Map.of("find", "x", "replace", "y")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("path");
    }

    @Test
    void missingFindArgumentRaisesError() {
        ProposePatchTool tool = toolWith(new RoomFileSnapshotStore());
        assertThatThrownBy(() -> tool.execute(UUID.randomUUID(),
                Map.of("path", "A.java", "replace", "y")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("find");
    }

    @Test
    void unknownFileRaisesError() {
        ProposePatchTool tool = toolWith(new RoomFileSnapshotStore());
        assertThatThrownBy(() -> tool.execute(UUID.randomUUID(),
                Map.of("path", "Missing.java", "find", "x", "replace", "y")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("not indexed");
    }

    @Test
    void findStringMissingRaisesError() {
        RoomFileSnapshotStore store = new RoomFileSnapshotStore();
        UUID room = UUID.randomUUID();
        store.put(room, "Hello.java", "class Hello {}");
        ProposePatchTool tool = toolWith(store);

        assertThatThrownBy(() -> tool.execute(room,
                Map.of("path", "Hello.java", "find", "nonexistent", "replace", "y")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("was not found");
    }

    @Test
    void ambiguousFindStringRaisesError() {
        RoomFileSnapshotStore store = new RoomFileSnapshotStore();
        UUID room = UUID.randomUUID();
        store.put(room, "X.java", "foo bar foo");
        ProposePatchTool tool = toolWith(store);

        assertThatThrownBy(() -> tool.execute(room,
                Map.of("path", "X.java", "find", "foo", "replace", "baz")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("multiple times");
    }

    @Test
    void successfulProposalReturnsParseableJsonPayload() throws Exception {
        RoomFileSnapshotStore store = new RoomFileSnapshotStore();
        UUID room = UUID.randomUUID();
        store.put(room, "Auth.java", "public String refreshToken() { return null; }");
        ProposePatchTool tool = toolWith(store);

        String raw = tool.execute(room, Map.of(
                "path", "Auth.java",
                "find", "return null;",
                "replace", "return token + \":fresh\";",
                "rationale", "stub returned null"
        ));

        JsonNode payload = mapper.readTree(raw);
        assertThat(payload.get("kind").asText()).isEqualTo("patch_proposal");
        assertThat(payload.get("patchId").asText()).startsWith("patch_");
        assertThat(payload.get("path").asText()).isEqualTo("Auth.java");
        assertThat(payload.get("find").asText()).isEqualTo("return null;");
        assertThat(payload.get("replace").asText()).contains("fresh");
        assertThat(payload.get("rationale").asText()).contains("stub");
    }

    @Test
    void emptyReplaceIsAcceptedAndTreatedAsDeletion() throws Exception {
        RoomFileSnapshotStore store = new RoomFileSnapshotStore();
        UUID room = UUID.randomUUID();
        store.put(room, "X.java", "before DELETEME after");
        ProposePatchTool tool = toolWith(store);

        // No "replace" key at all — should default to "" (deletion).
        String raw = tool.execute(room, Map.of(
                "path", "X.java",
                "find", "DELETEME "
        ));
        JsonNode payload = mapper.readTree(raw);
        assertThat(payload.get("replace").asText()).isEmpty();
    }

    @Test
    void patchIdsAreUniqueAcrossCalls() throws Exception {
        RoomFileSnapshotStore store = new RoomFileSnapshotStore();
        UUID room = UUID.randomUUID();
        store.put(room, "X.java", "a\nb\nc");
        ProposePatchTool tool = toolWith(store);

        String first = tool.execute(room, Map.of("path", "X.java", "find", "a", "replace", "A"));
        String second = tool.execute(room, Map.of("path", "X.java", "find", "b", "replace", "B"));

        String id1 = mapper.readTree(first).get("patchId").asText();
        String id2 = mapper.readTree(second).get("patchId").asText();
        assertThat(id1).isNotEqualTo(id2);
    }
}
