package com.codeleon.auth.dto;

import com.codeleon.user.dto.UserResponse;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        UserResponse user
) {
}
