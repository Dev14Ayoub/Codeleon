package com.codeleon.ai.history;

import com.codeleon.room.Room;
import com.codeleon.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RoomChatMessageRepository extends JpaRepository<RoomChatMessage, UUID> {

    /**
     * Backing query for an invited member viewing their own conversation
     * in a room. Uses the (room_id, user_id, created_at) index from V7.
     */
    List<RoomChatMessage> findByRoomAndUserOrderByCreatedAtAsc(Room room, User user);

    /**
     * Backing query for the room owner reviewing every member's chat in
     * the room, chronologically across users. Uses the (room_id,
     * created_at) index from V7.
     */
    List<RoomChatMessage> findByRoomOrderByCreatedAtAsc(Room room);
}
