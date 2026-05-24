package com.codeleon.ai;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoomFileSnapshotStoreTest {

    @Test
    void putAndGetRoundTripIsLossless() {
        RoomFileSnapshotStore store = new RoomFileSnapshotStore();
        UUID room = UUID.randomUUID();
        store.put(room, "App.java", "class App {}");

        assertThat(store.get(room, "App.java")).isEqualTo("class App {}");
        assertThat(store.fileCount(room)).isEqualTo(1);
    }

    @Test
    void putWithEmptyTextDeletesPath() {
        RoomFileSnapshotStore store = new RoomFileSnapshotStore();
        UUID room = UUID.randomUUID();
        store.put(room, "X.java", "stuff");
        store.put(room, "X.java", "");
        assertThat(store.get(room, "X.java")).isNull();
        assertThat(store.fileCount(room)).isZero();
    }

    @Test
    void listPathsIsSortedAlphabetically() {
        RoomFileSnapshotStore store = new RoomFileSnapshotStore();
        UUID room = UUID.randomUUID();
        store.put(room, "b.java", "b");
        store.put(room, "a.java", "a");
        store.put(room, "c.java", "c");
        assertThat(store.listPaths(room)).containsExactly("a.java", "b.java", "c.java");
    }

    @Test
    void deletePathScopedToRoomOnly() {
        RoomFileSnapshotStore store = new RoomFileSnapshotStore();
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        store.put(roomA, "shared.java", "A");
        store.put(roomB, "shared.java", "B");
        store.deletePath(roomA, "shared.java");
        assertThat(store.get(roomA, "shared.java")).isNull();
        assertThat(store.get(roomB, "shared.java")).isEqualTo("B");
    }

    @Test
    void deleteRoomWipesAllPaths() {
        RoomFileSnapshotStore store = new RoomFileSnapshotStore();
        UUID room = UUID.randomUUID();
        store.put(room, "a", "1");
        store.put(room, "b", "2");
        store.deleteRoom(room);
        assertThat(store.fileCount(room)).isZero();
        assertThat(store.listPaths(room)).isEmpty();
    }

    @Test
    void unknownRoomReturnsEmptyState() {
        RoomFileSnapshotStore store = new RoomFileSnapshotStore();
        UUID unknown = UUID.randomUUID();
        assertThat(store.get(unknown, "x")).isNull();
        assertThat(store.listPaths(unknown)).isEmpty();
        assertThat(store.fileCount(unknown)).isZero();
    }
}
