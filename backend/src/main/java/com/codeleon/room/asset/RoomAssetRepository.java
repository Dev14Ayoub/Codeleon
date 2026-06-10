package com.codeleon.room.asset;

import com.codeleon.room.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomAssetRepository extends JpaRepository<RoomAsset, UUID> {

    List<RoomAsset> findByRoomOrderByPathAsc(Room room);

    Optional<RoomAsset> findByRoomAndPath(Room room, String path);

    boolean existsByRoomAndPath(Room room, String path);

    long countByRoom(Room room);
}
