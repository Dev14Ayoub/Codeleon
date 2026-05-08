package com.codeleon.room;

import com.codeleon.room.enums.RoomVisibility;
import com.codeleon.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    Optional<Room> findByInviteCode(String inviteCode);

    List<Room> findByVisibilityOrderByCreatedAtDesc(RoomVisibility visibility);

    List<Room> findAllByOrderByCreatedAtDesc();

    long countByOwner(User owner);

    long countByVisibility(RoomVisibility visibility);
}
