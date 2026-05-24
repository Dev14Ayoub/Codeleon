package com.codeleon.ai.agent.tools;

import com.codeleon.ai.RoomFileSnapshotStore;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ListFilesToolTest {

    @Test
    void emptyRoomReturnsExplanatoryMessage() {
        ListFilesTool tool = new ListFilesTool(new RoomFileSnapshotStore());
        String out = tool.execute(UUID.randomUUID(), Map.of());
        assertThat(out).contains("no files indexed");
    }

    @Test
    void populatedRoomListsEveryPathOnce() {
        RoomFileSnapshotStore store = new RoomFileSnapshotStore();
        UUID room = UUID.randomUUID();
        store.put(room, "A.java", "a");
        store.put(room, "B.java", "b");
        store.put(room, "C.java", "c");

        String out = new ListFilesTool(store).execute(room, Map.of());
        assertThat(out).contains("3 files");
        assertThat(out).contains("A.java").contains("B.java").contains("C.java");
    }
}
