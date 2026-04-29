package com.codeleon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codeleon.security")
public record SecurityProperties(
        String jwtSecret,
        long accessTokenExpirationMs,
        long refreshTokenExpirationMs,
        String corsAllowedOrigins
) {
}
