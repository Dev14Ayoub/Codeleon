package com.codeleon.room;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RoomFileRepository extends JpaRepository<RoomFile, UUID> {

    Optional<RoomFile> findByRoomAndPath(Room room, String path);
}
