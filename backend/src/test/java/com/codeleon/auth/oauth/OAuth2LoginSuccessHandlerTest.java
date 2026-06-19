package com.codeleon.auth.oauth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuth2LoginSuccessHandlerTest {

    @Test
    void extractsGithubProfile() {
        Map<String, Object> attrs = Map.of(
                "id", 123456,
                "email", "octocat@github.com",
                "name", "The Octocat",
                "avatar_url", "https://avatars.githubusercontent.com/u/123456"
        );

        OAuth2LoginSuccessHandler.ProviderProfile profile =
                OAuth2LoginSuccessHandler.extractProfile("github", attrs);

        assertThat(profile.subject()).isEqualTo("123456");
        assertThat(profile.email()).isEqualTo("octocat@github.com");
        assertThat(profile.name()).isEqualTo("The Octocat");
        assertThat(profile.avatarUrl()).isEqualTo("https://avatars.githubusercontent.com/u/123456");
        // GitHub exposes only verified primary/public emails.
        assertThat(profile.emailVerified()).isTrue();
    }

    @Test
    void extractsGoogleProfile() {
        Map<String, Object> attrs = Map.of(
                "sub", "112233445566",
                "email", "alice@gmail.com",
                "email_verified", true,
                "name", "Alice Example",
                "picture", "https://lh3.googleusercontent.com/a/alice"
        );

        OAuth2LoginSuccessHandler.ProviderProfile profile =
                OAuth2LoginSuccessHandler.extractProfile("google", attrs);

        assertThat(profile.subject()).isEqualTo("112233445566");
        assertThat(profile.email()).isEqualTo("alice@gmail.com");
        assertThat(profile.name()).isEqualTo("Alice Example");
        assertThat(profile.avatarUrl()).isEqualTo("https://lh3.googleusercontent.com/a/alice");
        assertThat(profile.emailVerified()).isTrue();
    }

    @Test
    void googleEmailUnverifiedWhenClaimMissingOrFalse() {
        OAuth2LoginSuccessHandler.ProviderProfile missing =
                OAuth2LoginSuccessHandler.extractProfile("google", Map.of(
                        "sub", "1", "email", "x@gmail.com"));
        assertThat(missing.emailVerified()).isFalse();

        OAuth2LoginSuccessHandler.ProviderProfile explicitFalse =
                OAuth2LoginSuccessHandler.extractProfile("google", Map.of(
                        "sub", "2", "email", "y@gmail.com", "email_verified", false));
        assertThat(explicitFalse.emailVerified()).isFalse();

        // Google occasionally serializes the claim as a string.
        OAuth2LoginSuccessHandler.ProviderProfile stringTrue =
                OAuth2LoginSuccessHandler.extractProfile("google", Map.of(
                        "sub", "3", "email", "z@gmail.com", "email_verified", "true"));
        assertThat(stringTrue.emailVerified()).isTrue();
    }

    @Test
    void rejectsUnknownProvider() {
        assertThatThrownBy(() ->
                OAuth2LoginSuccessHandler.extractProfile("facebook", Map.of("id", "x"))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toleratesMissingAttributes() {
        OAuth2LoginSuccessHandler.ProviderProfile profile =
                OAuth2LoginSuccessHandler.extractProfile("github", Map.of("id", 1));
        assertThat(profile.subject()).isEqualTo("1");
        assertThat(profile.email()).isNull();
        assertThat(profile.name()).isNull();
        assertThat(profile.avatarUrl()).isNull();
    }

    @Test
    void prefersPrimaryVerifiedGithubEmail() {
        List<OAuth2LoginSuccessHandler.GithubEmail> emails = List.of(
                new OAuth2LoginSuccessHandler.GithubEmail("secondary@example.com", false, true, null),
                new OAuth2LoginSuccessHandler.GithubEmail("primary@example.com", true, true, "private")
        );

        assertThat(OAuth2LoginSuccessHandler.selectBestGithubEmail(emails))
                .contains("primary@example.com");
    }

    @Test
    void fallsBackToPrimaryGithubEmailWhenUnverified() {
        List<OAuth2LoginSuccessHandler.GithubEmail> emails = List.of(
                new OAuth2LoginSuccessHandler.GithubEmail("verified@example.com", false, true, null),
                new OAuth2LoginSuccessHandler.GithubEmail("primary@example.com", true, false, null)
        );

        assertThat(OAuth2LoginSuccessHandler.selectBestGithubEmail(emails))
                .contains("primary@example.com");
    }

    @Test
    void ignoresBlankGithubEmails() {
        List<OAuth2LoginSuccessHandler.GithubEmail> emails = List.of(
                new OAuth2LoginSuccessHandler.GithubEmail(" ", true, true, null)
        );

        assertThat(OAuth2LoginSuccessHandler.selectBestGithubEmail(emails)).isEmpty();
    }
}
