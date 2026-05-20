package com.codeleon.auth.oauth;

import java.time.Instant;

public record OAuthTokenDetails(
        String accessToken,
        String tokenType,
        String scopes,
        Instant expiresAt
) {
}
