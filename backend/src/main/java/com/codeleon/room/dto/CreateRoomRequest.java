package com.codeleon.room.dto;

import com.codeleon.room.enums.RoomVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateRoomRequest(
        @NotBlank
        @Size(min = 2, max = 120)
        String name,

        @Size(max = 500)
        String description,

        @NotNull
        RoomVisibility visibility
) {
}
