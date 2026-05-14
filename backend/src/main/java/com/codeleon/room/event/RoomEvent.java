package com.codeleon.room.event;

import com.codeleon.room.Room;
import com.codeleon.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only entry in the activity feed. One row per interesting
 * action — file CRUD, code execution, AI prompt, join — keyed by
 * room and the user who triggered it. The {@code type} string is
 * the contract between server and frontend; treat it as enum-like
 * even though the column is just a varchar.
 */
@Entity
@Table(name = "room_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomEvent {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    /**
     * Nullable so a deleted user does not cascade-delete history.
     * The matching foreign key uses ON DELETE SET NULL in V6.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false, length = 40)
    private String type;

    /**
     * Optional JSON payload — for example {@code {"path":"main.py"}}
     * for a file-rename event. Kept as plain text on purpose: H2 used
     * by the test profile lacks proper JSONB support and we never run
     * structural queries against the payload.
     */
    @Column
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
