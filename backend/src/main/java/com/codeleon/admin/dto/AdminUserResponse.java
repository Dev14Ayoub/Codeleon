package com.codeleon.admin.dto;

import com.codeleon.user.User;
import com.codeleon.user.UserRole;

import java.time.Instant;
import java.util.UUID;

/**
 * Comprehensive user view exposed to ADMINs.
 *
 * <p>Includes the OAuth subject and timestamps, but deliberately does NOT
 * include the bcrypt {@code passwordHash}: even one-way hashes leak
 * structural information and should never travel through an admin API
 * that could end up cached or screen-shared.</p>
 */
public record AdminUserResponse(
        UUID id,
        String fullName,
        String email,
        String avatarUrl,
        UserRole role,
        AuthMethod authMethod,
        String oauthProvider,
        String oauthSubject,
        Instant createdAt,
        Instant updatedAt,
        long ownedRoomsCount,
        long memberRoomsCount
) {
    public enum AuthMethod {
        PASSWORD,
        GITHUB,
        GOOGLE
    }

    public static AdminUserResponse of(User user, long owned, long member) {
        return new AdminUserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getAvatarUrl(),
                user.getRole(),
                resolveMethod(user),
                user.getOauthProvider(),
                user.getOauthSubject(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                owned,
                member
        );
    }

    private static AuthMethod resolveMethod(User user) {
        if (user.getOauthProvider() == null) return AuthMethod.PASSWORD;
        return switch (user.getOauthProvider().toLowerCase()) {
            case "github" -> AuthMethod.GITHUB;
            case "google" -> AuthMethod.GOOGLE;
            default -> AuthMethod.PASSWORD;
        };
    }
}
