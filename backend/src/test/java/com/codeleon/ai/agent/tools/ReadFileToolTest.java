package com.codeleon.ai.agent.tools;

import com.codeleon.ai.RoomFileSnapshotStore;
import com.codeleon.ai.agent.ToolExecutionException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadFileToolTest {

    @Test
    void missingPathArgumentRaisesToolError() {
        ReadFileTool tool = new ReadFileTool(new RoomFileSnapshotStore());
        assertThatThrownBy(() -> tool.execute(UUID.randomUUID(), Map.of()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("path");
    }

    @Test
    void unknownPathRaisesToolError() {
        ReadFileTool tool = new ReadFileTool(new RoomFileSnapshotStore());
        assertThatThrownBy(() -> tool.execute(UUID.randomUUID(), Map.of("path", "Missing.java")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("not indexed");
    }

    @Test
    void prefixesLinesWithNumbers() throws Exception {
        RoomFileSnapshotStore store = new RoomFileSnapshotStore();
        UUID room = UUID.randomUUID();
        store.put(room, "Hello.java", "line1\nline2\nline3\n");

        String out = new ReadFileTool(store).execute(room, Map.of("path", "Hello.java"));
        // Each source line should be visible with its 1-indexed number.
        assertThat(out).contains("1 | line1");
        assertThat(out).contains("2 | line2");
        assertThat(out).contains("3 | line3");
        assertThat(out).contains("Hello.java");
    }
}
