package com.codeleon.room.event;

import com.codeleon.room.Room;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RoomEventRepository extends JpaRepository<RoomEvent, UUID> {

    /**
     * Cross-room timeline for the dashboard sidebar: events from every
     * room in {@code roomIds}, newest first. The {@code since} bound lets
     * the frontend poll for "anything after the last event I already
     * have" instead of re-fetching the whole feed every 30 s.
     */
    List<RoomEvent> findByRoomIdInAndCreatedAtAfterOrderByCreatedAtDesc(
            List<UUID> roomIds, Instant since, Pageable pageable);

    /**
     * Same timeline without the since-bound, for the very first load.
     */
    List<RoomEvent> findByRoomIdInOrderByCreatedAtDesc(List<UUID> roomIds, Pageable pageable);

    /**
     * Per-room timeline (newest first) — backs a future in-room
     * activity panel; not wired into a controller yet.
     */
    List<RoomEvent> findByRoomOrderByCreatedAtDesc(Room room, Pageable pageable);
}
