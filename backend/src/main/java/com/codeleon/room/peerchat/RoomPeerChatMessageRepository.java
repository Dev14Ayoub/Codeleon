package com.codeleon.room.peerchat;

import com.codeleon.room.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RoomPeerChatMessageRepository extends JpaRepository<RoomPeerChatMessage, UUID> {

    /**
     * Last {@code limit} messages for a room, oldest first. The "oldest
     * first" projection matches how the frontend renders them (top to
     * bottom, latest at the bottom). Limit is enforced via JPQL +
     * setMaxResults pattern; native syntax in PostgreSQL would be
     * {@code LIMIT}.
     *
     * <p>Expired messages are excluded so a peer that mounts seconds
     * before the cleanup job fires never sees a row that's about to
     * disappear from under them.
     */
    @Query("SELECT m FROM RoomPeerChatMessage m " +
           "WHERE m.room = :room " +
           "AND (m.expiresAt IS NULL OR m.expiresAt > CURRENT_TIMESTAMP) " +
           "ORDER BY m.createdAt DESC")
    List<RoomPeerChatMessage> findRecentForRoom(@Param("room") Room room,
                                                org.springframework.data.domain.Pageable pageable);

    /**
     * Bulk-delete every message whose TTL ran out. Returns the number of
     * rows removed so the scheduled job can log it. Runs against the
     * partial index on {@code expires_at} so the work is O(expired-rows),
     * not O(table).
     */
    @Modifying
    @Query("DELETE FROM RoomPeerChatMessage m WHERE m.expiresAt IS NOT NULL AND m.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
