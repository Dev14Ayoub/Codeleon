package com.codeleon.user.dto;

import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @Size(min = 2, max = 120)
        String fullName,

        @Size(max = 500)
        String avatarUrl
) {
}
