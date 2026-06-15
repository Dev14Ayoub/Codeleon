package com.codeleon.auth.oauth;

import com.codeleon.auth.token.RefreshToken;
import com.codeleon.auth.token.RefreshTokenRepository;
import com.codeleon.config.JwtService;
import com.codeleon.config.SecurityProperties;
import com.codeleon.user.User;
import com.codeleon.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Runs after Spring Security finishes the OAuth2 dance with GitHub or
 * Google. We extract the provider attributes, find-or-create the local
 * {@link User}, mint a Codeleon JWT pair, and redirect the browser to the
 * frontend's {@code /auth/callback} with the tokens in the query string.
 *
 * <p>The redirect target is the first entry in
 * {@code codeleon.security.cors-allowed-origins} so the frontend ends up
 * on its own origin even when the backend is behind a different host.</p>
 */
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    private final UserService userService;
    private final JwtService jwtService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SecurityProperties securityProperties;
    private final ObjectProvider<OAuth2AuthorizedClientService> authorizedClientServiceProvider;
    private final RestClient.Builder restClientBuilder;
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {
        if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)) {
            redirectWithError(response, "oauth_unexpected_principal");
            return;
        }

        String provider = oauthToken.getAuthorizedClientRegistrationId();
        OAuth2User principal = oauthToken.getPrincipal();
        Map<String, Object> attributes = principal.getAttributes();

        ProviderProfile profile;
        try {
            profile = extractProfile(provider, attributes);
        } catch (RuntimeException ex) {
            log.warn("OAuth profile extraction failed for provider {}: {}", provider, ex.getMessage());
            redirectWithError(response, "oauth_profile_extraction_failed");
            return;
        }

        OAuthTokenDetails token = tokenDetails(provider, oauthToken);
        profile = resolveMissingProviderEmail(provider, profile, token);

        User user;
        try {
            user = userService.findOrCreateByOAuth(
                    provider,
                    profile.subject,
                    profile.email,
                    profile.name,
                    profile.avatarUrl,
                    token
            );
        } catch (RuntimeException ex) {
            log.warn("OAuth find-or-create failed for {}/{}: {}", provider, profile.subject, ex.getMessage());
            redirectWithError(response, blank(profile.email) ? "oauth_email_missing" : "oauth_link_conflict");
            return;
        }

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = generateRefreshToken();
        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(hashToken(refreshToken))
                .expiresAt(Instant.now().plusMillis(securityProperties.refreshTokenExpirationMs()))
                .revoked(false)
                .build());

        String base = UriComponentsBuilder.fromUriString(frontendOrigin())
                .path("/auth/callback")
                .build()
                .toUriString();
        // Tokens ride in the URL *fragment*, not the query string: fragments
        // are never sent to a server (so they don't land in Caddy/proxy access
        // logs) and are not included in the Referer header, which keeps the
        // access + refresh tokens out of logs and third-party requests.
        String target = base + "#accessToken=" + URLEncoder.encode(accessToken, StandardCharsets.UTF_8)
                + "&refreshToken=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8);

        log.info("OAuth login success: user={} provider={}", user.getEmail(), provider);
        redirectStrategy.sendRedirect(request, response, target);
    }

    private void redirectWithError(HttpServletResponse response, String code) throws IOException {
        String target = UriComponentsBuilder.fromUriString(frontendOrigin())
                .path("/login")
                .queryParam("oauth_error", code)
                .build()
                .toUriString();
        response.sendRedirect(target);
    }

    private String frontendOrigin() {
        String origins = securityProperties.corsAllowedOrigins();
        if (origins == null || origins.isBlank()) return "http://localhost:5173";
        return origins.split(",")[0].trim();
    }

    private OAuthTokenDetails tokenDetails(String provider, OAuth2AuthenticationToken oauthToken) {
        OAuth2AuthorizedClientService service = authorizedClientServiceProvider.getIfAvailable();
        if (service == null) return null;
        OAuth2AuthorizedClient client = service.loadAuthorizedClient(provider, oauthToken.getName());
        if (client == null || client.getAccessToken() == null) return null;

        OAuth2AccessToken accessToken = client.getAccessToken();
        String scopes = accessToken.getScopes().stream()
                .sorted()
                .collect(Collectors.joining(" "));
        return new OAuthTokenDetails(
                accessToken.getTokenValue(),
                accessToken.getTokenType().getValue(),
                scopes,
                accessToken.getExpiresAt()
        );
    }

    private ProviderProfile resolveMissingProviderEmail(
            String provider,
            ProviderProfile profile,
            OAuthTokenDetails token
    ) {
        if (!"github".equals(provider) || !blank(profile.email) || token == null || blank(token.accessToken())) {
            return profile;
        }

        String email = fetchGithubEmail(token.accessToken());
        if (blank(email)) {
            return profile;
        }
        return new ProviderProfile(profile.subject, email, profile.name, profile.avatarUrl);
    }

    private String fetchGithubEmail(String accessToken) {
        try {
            List<GithubEmail> emails = restClientBuilder.build()
                    .get()
                    .uri("https://api.github.com/user/emails")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .header(HttpHeaders.USER_AGENT, "Codeleon")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return selectBestGithubEmail(emails).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("Could not fetch GitHub email fallback: {}", ex.getMessage());
            return null;
        }
    }

    static Optional<String> selectBestGithubEmail(List<GithubEmail> emails) {
        if (emails == null || emails.isEmpty()) {
            return Optional.empty();
        }
        return emails.stream()
                .filter(Objects::nonNull)
                .filter(email -> !blank(email.email()))
                .min(Comparator
                        .comparing((GithubEmail email) -> !Boolean.TRUE.equals(email.primary()))
                        .thenComparing(email -> !Boolean.TRUE.equals(email.verified())))
                .map(GithubEmail::email);
    }

    static ProviderProfile extractProfile(String provider, Map<String, Object> attributes) {
        return switch (provider) {
            case "github" -> new ProviderProfile(
                    String.valueOf(attributes.get("id")),
                    asString(attributes.get("email")),
                    asString(attributes.get("name")),
                    asString(attributes.get("avatar_url"))
            );
            case "google" -> new ProviderProfile(
                    asString(attributes.get("sub")),
                    asString(attributes.get("email")),
                    asString(attributes.get("name")),
                    asString(attributes.get("picture"))
            );
            default -> throw new IllegalArgumentException("Unsupported OAuth provider: " + provider);
        };
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String generateRefreshToken() {
        byte[] bytes = new byte[64];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    record ProviderProfile(String subject, String email, String name, String avatarUrl) {
    }

    record GithubEmail(String email, Boolean primary, Boolean verified, String visibility) {
    }
}
