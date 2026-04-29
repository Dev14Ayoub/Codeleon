package com.codeleon.user.dto;

import java.util.UUID;

public record UserResponse(
        UUID id,
        String fullName,
        String email,
        String avatarUrl
) {
}
