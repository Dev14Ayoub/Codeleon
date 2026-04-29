package com.codeleon.room;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    Optional<Room> findByInviteCode(String inviteCode);

    List<Room> findByVisibilityOrderByCreatedAtDesc(com.codeleon.room.enums.RoomVisibility visibility);
}
