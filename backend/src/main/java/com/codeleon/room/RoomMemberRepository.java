package com.codeleon.room;

import com.codeleon.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomMemberRepository extends JpaRepository<RoomMember, UUID> {

    List<RoomMember> findByUser(User user);

    List<RoomMember> findByUserOrderByJoinedAtDesc(User user);

    Optional<RoomMember> findByRoomAndUser(Room room, User user);
}
