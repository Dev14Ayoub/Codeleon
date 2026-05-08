package com.codeleon.admin.dto;

import com.codeleon.room.Room;
import com.codeleon.room.enums.RoomVisibility;

import java.time.Instant;
import java.util.UUID;

public record AdminRoomResponse(
        UUID id,
        String name,
        String description,
        RoomVisibility visibility,
        String inviteCode,
        UUID ownerId,
        String ownerEmail,
        String ownerFullName,
        long memberCount,
        long fileCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static AdminRoomResponse of(Room room, long memberCount, long fileCount) {
        return new AdminRoomResponse(
                room.getId(),
                room.getName(),
                room.getDescription(),
                room.getVisibility(),
                room.getInviteCode(),
                room.getOwner() == null ? null : room.getOwner().getId(),
                room.getOwner() == null ? null : room.getOwner().getEmail(),
                room.getOwner() == null ? null : room.getOwner().getFullName(),
                memberCount,
                fileCount,
                room.getCreatedAt(),
                room.getUpdatedAt()
        );
    }
}
