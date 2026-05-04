package com.codeleon.room;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomFileRepository extends JpaRepository<RoomFile, UUID> {

    Optional<RoomFile> findByRoomAndPath(Room room, String path);

    Optional<RoomFile> findByIdAndRoom(UUID id, Room room);

    List<RoomFile> findByRoomOrderByPathAsc(Room room);

    boolean existsByRoomAndPath(Room room, String path);
}
