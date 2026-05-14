package com.codeleon.room.dto;

import com.codeleon.room.enums.RoomMemberRole;
import com.codeleon.room.enums.RoomVisibility;

import java.time.Instant;
import java.util.UUID;

public record RoomResponse(
        UUID id,
        String name,
        String description,
        RoomVisibility visibility,
        String inviteCode,
        UUID ownerId,
        String ownerName,
        RoomMemberRole currentUserRole,
        long fileCount,
        long memberCount,
        boolean pinned,
        boolean archived,
        // Denormalised from rooms.last_edited_by_id — null until the room
        // has seen its first activity event. Flat id+name pair to stay
        // consistent with ownerId/ownerName above.
        UUID lastEditedById,
        String lastEditedByName,
        Instant createdAt,
        Instant updatedAt
) {
}
