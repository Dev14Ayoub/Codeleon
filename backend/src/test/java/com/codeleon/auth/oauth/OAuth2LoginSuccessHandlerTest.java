package com.codeleon.auth.oauth;

import org.junit.jupiter.api.Test;

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
    }

    @Test
    void extractsGoogleProfile() {
        Map<String, Object> attrs = Map.of(
                "sub", "112233445566",
                "email", "alice@gmail.com",
                "name", "Alice Example",
                "picture", "https://lh3.googleusercontent.com/a/alice"
        );

        OAuth2LoginSuccessHandler.ProviderProfile profile =
                OAuth2LoginSuccessHandler.extractProfile("google", attrs);

        assertThat(profile.subject()).isEqualTo("112233445566");
        assertThat(profile.email()).isEqualTo("alice@gmail.com");
        assertThat(profile.name()).isEqualTo("Alice Example");
        assertThat(profile.avatarUrl()).isEqualTo("https://lh3.googleusercontent.com/a/alice");
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
}
