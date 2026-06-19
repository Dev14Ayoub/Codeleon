package com.codeleon.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomFileIndexStateRepository extends JpaRepository<RoomFileIndexState, UUID> {

    List<RoomFileIndexState> findByRoomId(UUID roomId);

    Optional<RoomFileIndexState> findByRoomIdAndPath(UUID roomId, String path);

    @Modifying
    @Query("DELETE FROM RoomFileIndexState s WHERE s.roomId = :roomId")
    void deleteByRoomId(@Param("roomId") UUID roomId);

    @Modifying
    @Query("DELETE FROM RoomFileIndexState s WHERE s.roomId = :roomId AND s.path = :path")
    void deleteByRoomIdAndPath(@Param("roomId") UUID roomId, @Param("path") String path);
}
