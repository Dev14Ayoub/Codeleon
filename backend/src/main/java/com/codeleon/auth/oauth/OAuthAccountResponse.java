package com.codeleon.auth.oauth;

import java.time.Instant;

public record OAuthAccountResponse(
        String provider,
        String email,
        String scopes,
        Instant expiresAt,
        Instant updatedAt
) {

    public static OAuthAccountResponse of(OAuthAccount account) {
        return new OAuthAccountResponse(
                account.getProvider(),
                account.getEmail(),
                account.getScopes(),
                account.getExpiresAt(),
                account.getUpdatedAt()
        );
    }
}
