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
        Instant createdAt,
        Instant updatedAt
) {
}
