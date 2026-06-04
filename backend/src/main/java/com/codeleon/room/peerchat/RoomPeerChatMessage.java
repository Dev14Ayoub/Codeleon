package com.codeleon.room.peerchat;

import com.codeleon.room.Room;
import com.codeleon.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One peer-to-peer chat message in a room. Distinct from the AI chat
 * history in {@code RoomChatMessage} (V7) — that one is per-user
 * conversations with the assistant; this one is many-to-many across
 * the room's members.
 *
 * <p>An attachment, when present, lives in {@link #fileBytes} alongside
 * its metadata. The upload endpoint caps the byte size to keep Postgres
 * dumps reasonable.
 */
@Entity
@Table(name = "room_peer_chat_messages")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RoomPeerChatMessage {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    /** Nullable: ON DELETE SET NULL when the user account is removed. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** Cached at write time so the message survives the user being deleted. */
    @Column(name = "user_name", nullable = false, length = 180)
    private String userName;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "file_type", length = 120)
    private String fileType;

    @Column(name = "file_size")
    private Integer fileSize;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "file_bytes")
    private byte[] fileBytes;

    /**
     * Cached audio duration in milliseconds — only set when this message
     * is a voice message (i.e. {@link #fileType} starts with "audio/").
     * The client measures it at recording time; we never decode the
     * audio server-side just to compute this.
     */
    @Column(name = "audio_duration_ms")
    private Integer audioDurationMs;

    /**
     * When this message must be purged from the database. NULL means
     * "never" (regular text / file messages). Voice messages get this
     * column set to {@code now() + voice TTL} (24h by default) so the
     * scheduled cleanup job can delete them on a partial index lookup.
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
