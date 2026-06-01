package com.codeleon.room.peerchat;

import com.codeleon.room.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RoomPeerChatMessageRepository extends JpaRepository<RoomPeerChatMessage, UUID> {

    /**
     * Last {@code limit} messages for a room, oldest first. The "oldest
     * first" projection matches how the frontend renders them (top to
     * bottom, latest at the bottom). Limit is enforced via JPQL +
     * setMaxResults pattern; native syntax in PostgreSQL would be
     * {@code LIMIT}.
     */
    @Query("SELECT m FROM RoomPeerChatMessage m WHERE m.room = :room ORDER BY m.createdAt DESC")
    List<RoomPeerChatMessage> findRecentForRoom(@Param("room") Room room,
                                                org.springframework.data.domain.Pageable pageable);
}
