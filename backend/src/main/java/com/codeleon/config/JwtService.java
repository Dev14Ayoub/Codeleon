package com.codeleon.config;

import com.codeleon.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.function.Function;

@Service
public class JwtService {

    private final SecurityProperties properties;

    public JwtService(SecurityProperties properties) {
        this.properties = properties;
    }

    /**
     * Fail fast at boot if {@code JWT_SECRET} was not configured. Without
     * this, a deploy that forgets {@code --env-file} silently falls back
     * to the placeholder default in {@code application.yml} — a value
     * that lives in the public source tree, meaning anyone can forge an
     * admin token. We refuse to start the JVM rather than open that hole.
     */
    @PostConstruct
    void validateSecret() {
        String secret = properties.jwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET is not set. Refusing to boot — auth would silently fall back to a known default.");
        }
        if (secret.startsWith("replace_me")) {
            throw new IllegalStateException(
                    "JWT_SECRET still contains the placeholder default from application.yml / .env.example. "
                            + "Set a real, random secret (>= 32 chars) in .env before deploying.");
        }
        // Pings the key construction path so a too-short secret surfaces
        // now (boot) rather than on the first user login.
        try {
            secretKey();
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "JWT_SECRET is too weak for HS256 (needs >= 32 bytes of key material): " + ex.getMessage(), ex);
        }
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("userId", user.getId().toString())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(properties.accessTokenExpirationMs())))
                .signWith(secretKey())
                .compact();
    }

    public String extractEmail(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(extractAllClaims(token).get("userId", String.class));
    }

    public boolean isValid(String token, User user) {
        return extractEmail(token).equals(user.getEmail()) && !isExpired(token);
    }

    private boolean isExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        return resolver.apply(extractAllClaims(token));
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey secretKey() {
        String secret = properties.jwtSecret();
        if (secret.matches("^[A-Za-z0-9+/=]+$")) {
            try {
                return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
            } catch (RuntimeException ignored) {
                // Fall back to raw bytes for developer-friendly local secrets.
            }
        }
        return Keys.hmacShaKeyFor(secret.getBytes());
    }
}
