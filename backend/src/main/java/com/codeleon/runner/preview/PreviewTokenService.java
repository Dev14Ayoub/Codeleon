package com.codeleon.runner.preview;

import com.codeleon.config.SecurityProperties;
import jakarta.servlet.http.Cookie;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * Issues and verifies short-lived, room-scoped preview access tokens carried in
 * an HttpOnly cookie. The reverse proxy ({@link PreviewProxyController}) cannot
 * use the SPA's bearer JWT — an {@code <iframe>} sends no Authorization header —
 * so a signed cookie scoped to {@code /api/v1/preview} authorizes both the
 * top-level navigation and every sub-resource the embedded app requests.
 *
 * <p>Token = {@code base64url(payload) "." base64url(HMAC-SHA256(payload))},
 * where payload is {@code roomId:expiryEpochMs}. The HMAC key is derived from
 * the JWT secret (with domain separation), so no new env var is required.
 */
@Component
public class PreviewTokenService {

    public static final String COOKIE_NAME = "codeleon_preview";
    /** Cookie lifetime — kept in line with the preview's max-session-ms. */
    public static final Duration TTL = Duration.ofMinutes(30);

    private static final String HMAC_ALG = "HmacSHA256";

    private final SecretKeySpec key;

    public PreviewTokenService(SecurityProperties properties) {
        this.key = deriveKey(properties.jwtSecret());
    }

    private static SecretKeySpec deriveKey(String secret) {
        try {
            byte[] material = MessageDigest.getInstance("SHA-256")
                    .digest(("preview-cookie:" + (secret == null ? "" : secret)).getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(material, HMAC_ALG);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot derive the preview token key", ex);
        }
    }

    /** Mints a token authorizing preview access to {@code roomId} until TTL. */
    public String issue(UUID roomId) {
        String payload = roomId.toString() + ':' + (System.currentTimeMillis() + TTL.toMillis());
        return b64(payload.getBytes(StandardCharsets.UTF_8)) + '.' + b64(sign(payload));
    }

    /** True if any cookie carries a valid, unexpired token for {@code roomId}. */
    public boolean isValid(Cookie[] cookies, UUID roomId) {
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName()) && isValidToken(cookie.getValue(), roomId)) {
                return true;
            }
        }
        return false;
    }

    boolean isValidToken(String token, UUID roomId) {
        if (token == null) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return false;
        }
        byte[] payloadBytes;
        byte[] signature;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(token.substring(0, dot));
            signature = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        } catch (IllegalArgumentException ex) {
            return false;
        }
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        // Constant-time compare to avoid leaking the signature via timing.
        if (!MessageDigest.isEqual(sign(payload), signature)) {
            return false;
        }
        int colon = payload.lastIndexOf(':');
        if (colon <= 0) {
            return false;
        }
        long expiry;
        try {
            expiry = Long.parseLong(payload.substring(colon + 1));
        } catch (NumberFormatException ex) {
            return false;
        }
        return roomId.toString().equals(payload.substring(0, colon))
                && System.currentTimeMillis() < expiry;
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(key);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign the preview token", ex);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
