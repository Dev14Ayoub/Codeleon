package com.codeleon.room.dto;

import com.codeleon.room.RoomFile;

import java.time.Instant;
import java.util.UUID;

public record RoomFileResponse(
        UUID id,
        String path,
        String language,
        Instant createdAt,
        Instant updatedAt
) {
    public static RoomFileResponse from(RoomFile file) {
        return new RoomFileResponse(
                file.getId(),
                file.getPath(),
                file.getLanguage(),
                file.getCreatedAt(),
                file.getUpdatedAt()
        );
    }
}
