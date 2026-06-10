package com.codeleon.room.asset;

import com.codeleon.room.Room;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A binary asset belonging to a room (image, font, media…). Stored as bytes in
 * Postgres — the collaborative Y.Doc only holds text files, so binary content
 * lives here instead. Materialized into the sandbox/preview workspace at run
 * time so static sites and projects find their images on disk.
 */
@Entity
@Table(name = "room_assets")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomAsset {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @Column(nullable = false, length = 255)
    private String path;

    @Column(name = "content_type", length = 150)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "bytes", nullable = false)
    private byte[] bytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
