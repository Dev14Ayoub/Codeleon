package com.codeleon.room;

import com.codeleon.room.enums.RoomMemberRole;
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
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "room_members")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomMember {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoomMemberRole role;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @PrePersist
    void onCreate() {
        id = id == null ? UUID.randomUUID() : id;
        joinedAt = joinedAt == null ? Instant.now() : joinedAt;
    }
}
