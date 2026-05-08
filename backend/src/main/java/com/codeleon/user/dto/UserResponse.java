package com.codeleon.user.dto;

import com.codeleon.user.UserRole;

import java.util.UUID;

public record UserResponse(
        UUID id,
        String fullName,
        String email,
        String avatarUrl,
        UserRole role
) {
}
