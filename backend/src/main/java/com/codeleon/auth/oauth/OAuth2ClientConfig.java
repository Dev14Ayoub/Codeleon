package com.codeleon.auth.oauth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Programmatically builds the OAuth2 client registry from optional env vars.
 *
 * <p>We deliberately do not use Spring Boot's
 * {@code spring.security.oauth2.client.registration.*} auto-configuration:
 * its registry adapter throws when a configured registration has a blank
 * client-id, which breaks the "Codeleon boots fine without OAuth credentials"
 * contract. Building the repository ourselves lets us silently skip any
 * provider whose env vars are unset.</p>
 *
 * <p>The whole bean is gated on at least one provider being configured —
 * otherwise the OAuth2 login chain stays inactive and Spring Security never
 * mounts the {@code /oauth2/**} filter.</p>
 */
@Configuration
public class OAuth2ClientConfig {

    @Value("${codeleon.oauth.github.client-id:}")
    private String githubClientId;

    @Value("${codeleon.oauth.github.client-secret:}")
    private String githubClientSecret;

    @Value("${codeleon.oauth.google.client-id:}")
    private String googleClientId;

    @Value("${codeleon.oauth.google.client-secret:}")
    private String googleClientSecret;

    @Bean
    @ConditionalOnExpression(
            "!'${codeleon.oauth.github.client-id:}'.isBlank() || " +
            "!'${codeleon.oauth.google.client-id:}'.isBlank()"
    )
    public ClientRegistrationRepository clientRegistrationRepository() {
        List<ClientRegistration> registrations = new ArrayList<>();

        if (!githubClientId.isBlank()) {
            registrations.add(CommonOAuth2Provider.GITHUB
                    .getBuilder("github")
                    .clientId(githubClientId)
                    .clientSecret(githubClientSecret)
                    .scope("read:user", "user:email")
                    .build());
        }
        if (!googleClientId.isBlank()) {
            registrations.add(CommonOAuth2Provider.GOOGLE
                    .getBuilder("google")
                    .clientId(googleClientId)
                    .clientSecret(googleClientSecret)
                    .scope("openid", "profile", "email")
                    .build());
        }
        return new InMemoryClientRegistrationRepository(registrations);
    }

    /**
     * Custom OIDC ID-token decoder factory that tolerates a five-minute
     * clock skew between Codeleon and the OIDC provider (Google in our
     * case). Spring Security's default tolerance is 60 seconds, which is
     * tight enough to break sign-in on a developer machine whose system
     * clock has drifted — we observed that crash on Windows + Docker
     * Desktop where the host clock can fall a few minutes behind.
     */
    @Bean
    public OidcIdTokenDecoderFactory oidcIdTokenDecoderFactory() {
        OidcIdTokenDecoderFactory factory = new OidcIdTokenDecoderFactory();
        factory.setJwtValidatorFactory(registration -> {
            OAuth2TokenValidator<Jwt> idToken = new OidcIdTokenValidator(registration);
            OAuth2TokenValidator<Jwt> timestamps = new JwtTimestampValidator(Duration.ofMinutes(5));
            return new DelegatingOAuth2TokenValidator<>(idToken, timestamps);
        });
        return factory;
    }
}
