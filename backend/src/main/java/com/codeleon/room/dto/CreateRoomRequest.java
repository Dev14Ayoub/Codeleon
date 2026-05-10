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
        RoomVisibility visibility,

        /**
         * Optional template id from the catalogue (see classpath:templates/).
         * When present, the new room is created with the template's files
         * already materialised so the user lands in a populated workspace.
         * When null or blank the existing behaviour applies — the room
         * starts empty and {@code RoomFileService.loadOrInitRoomSnapshot}
         * auto-creates a single default {@code main} file on first open.
         */
        String templateId
) {
}
