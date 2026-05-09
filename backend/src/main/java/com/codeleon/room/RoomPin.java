package com.codeleon.room;

import com.codeleon.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-user pin for a room. The composite key (userId, roomId) keeps
 * pinning idempotent: writing the same row twice is a no-op rather than
 * creating duplicates, which removes the need for a separate uniqueness
 * check in the service layer.
 */
@Entity
@Table(name = "room_pins")
@IdClass(RoomPin.PinId.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomPin {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @Column(name = "pinned_at", nullable = false)
    private Instant pinnedAt;

    @PrePersist
    void onCreate() {
        if (pinnedAt == null) {
            pinnedAt = Instant.now();
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PinId implements Serializable {
        private UUID user;
        private UUID room;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PinId other)) return false;
            return Objects.equals(user, other.user) && Objects.equals(room, other.room);
        }

        @Override
        public int hashCode() {
            return Objects.hash(user, room);
        }
    }
}
