package com.codeleon.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
 * Persistent baseline of what the RAG pipeline has indexed for a room: one
 * row per file, keyed by (room_id, path), holding the SHA-256 of the exact
 * text that was embedded. Lets any client diff the project's current content
 * against the server's view and re-embed only what changed — across
 * refreshes, browser tabs and collaborators.
 *
 * <p>{@code roomId} is stored as a plain UUID column rather than a
 * {@code @ManyToOne Room} because the indexer only ever works with room ids
 * and never needs to load the Room entity. The DB-level
 * {@code ON DELETE CASCADE} (see V12) handles cleanup when a room is removed.
 */
@Entity
@Table(name = "room_file_index_state")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomFileIndexState {

    @Id
    private UUID id;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(nullable = false, length = 1024)
    private String path;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "indexed_at", nullable = false)
    private Instant indexedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (indexedAt == null) indexedAt = Instant.now();
    }
}
