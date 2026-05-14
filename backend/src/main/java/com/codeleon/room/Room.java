package com.codeleon.room;

import com.codeleon.room.enums.RoomVisibility;
import com.codeleon.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rooms")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Room {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoomVisibility visibility;

    @Column(name = "invite_code", nullable = false, unique = true, length = 80)
    private String inviteCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    /**
     * Yjs Y.Doc snapshot for the whole room. The Y.Doc holds one Y.Text per
     * file path inside the room, so this single byte[] persists state for
     * every file at once.
     */
    @Column(name = "state_update")
    private byte[] stateUpdate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Set when the owner archives the room. Archived rooms are hidden from
     * the dashboard's default listing but otherwise functional — kept as
     * a soft-delete so an archive/unarchive toggle is reversible without
     * losing files, members, or invite history.
     */
    @Column(name = "archived_at")
    private Instant archivedAt;

    /**
     * Denormalised pointer to the last user who emitted a room_event here.
     * Kept on the room row so the dashboard listing can show "Last edited
     * by X" without a per-card join into room_events; refreshed by
     * {@code RoomEventService.emit} alongside every event insert.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_edited_by_id")
    private User lastEditedBy;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        id = id == null ? UUID.randomUUID() : id;
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
