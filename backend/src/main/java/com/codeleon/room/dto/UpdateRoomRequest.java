package com.codeleon.room.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Partial update for a room — the owner can rename the project and
 * change its description. Visibility, owner, and invite code are
 * intentionally out of scope here and have their own endpoints.
 */
public record UpdateRoomRequest(
        @NotBlank
        @Size(min = 2, max = 120)
        String name,

        @Size(max = 500)
        String description
) {
}
