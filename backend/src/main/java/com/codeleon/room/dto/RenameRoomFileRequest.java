package com.codeleon.room.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RenameRoomFileRequest(
        @NotBlank
        @Size(min = 1, max = 255)
        @Pattern(
                regexp = "^[A-Za-z0-9._-][A-Za-z0-9._/ -]*$",
                message = "Path may contain letters, digits, dot, dash, underscore, slash, space"
        )
        String path
) {
}
