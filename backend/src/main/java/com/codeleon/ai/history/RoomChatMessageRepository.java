package com.codeleon.ai.history;

import com.codeleon.room.Room;
import com.codeleon.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Lists the members who have written at least one message in this
     * room, with their message count. Orphan rows whose user_id was
     * SET NULL by a deletion are skipped so the picker only offers real
     * authors. Ordered by most recent activity so the owner sees the
     * latest writer first — useful when many members share the room.
     */
    @Query("""
            SELECT new com.codeleon.ai.history.ChatThreadSummary(
                m.user.id, m.user.fullName, COUNT(m)
            )
            FROM RoomChatMessage m
            WHERE m.room = :room AND m.user IS NOT NULL
            GROUP BY m.user.id, m.user.fullName
            ORDER BY MAX(m.createdAt) DESC
            """)
    List<ChatThreadSummary> findThreadsByRoom(@Param("room") Room room);
}
