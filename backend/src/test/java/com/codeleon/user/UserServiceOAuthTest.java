package com.codeleon.user;

import com.codeleon.auth.oauth.OAuthAccount;
import com.codeleon.auth.oauth.OAuthAccountRepository;
import com.codeleon.auth.oauth.OAuthTokenDetails;
import com.codeleon.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceOAuthTest {

    @Test
    void returnsExistingUserWhenProviderSubjectMatches() {
        UserRepository repo = mock(UserRepository.class);
        OAuthAccountRepository oauthRepo = oauthRepo();
        UserService service = new UserService(repo, oauthRepo);

        User existing = User.builder()
                .fullName("Old Name")
                .email("user@example.com")
                .role(UserRole.USER)
                .oauthProvider("github")
                .oauthSubject("12345")
                .build();
        when(repo.findByOauthProviderAndOauthSubject("github", "12345"))
                .thenReturn(Optional.of(existing));
        when(repo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = service.findOrCreateByOAuth(
                "github", "12345", "user@example.com", "New Name", "https://avatar"
        );

        assertThat(result).isSameAs(existing);
        // Profile updated → save called once.
        verify(repo, times(1)).save(any(User.class));
        assertThat(existing.getFullName()).isEqualTo("New Name");
        assertThat(existing.getAvatarUrl()).isEqualTo("https://avatar");
    }

    @Test
    void doesNotSaveWhenProfileIsUnchanged() {
        UserRepository repo = mock(UserRepository.class);
        OAuthAccountRepository oauthRepo = oauthRepo();
        UserService service = new UserService(repo, oauthRepo);

        User existing = User.builder()
                .fullName("Same Name")
                .email("user@example.com")
                .role(UserRole.USER)
                .oauthProvider("google")
                .oauthSubject("abcd-sub")
                .avatarUrl("https://pic")
                .build();
        when(repo.findByOauthProviderAndOauthSubject("google", "abcd-sub"))
                .thenReturn(Optional.of(existing));

        service.findOrCreateByOAuth(
                "google", "abcd-sub", "user@example.com", "Same Name", "https://pic"
        );

        verify(repo, never()).save(any(User.class));
    }

    @Test
    void createsNewUserWhenEmailIsAlsoUnknown() {
        UserRepository repo = mock(UserRepository.class);
        OAuthAccountRepository oauthRepo = oauthRepo();
        UserService service = new UserService(repo, oauthRepo);

        when(repo.findByOauthProviderAndOauthSubject(any(), any())).thenReturn(Optional.empty());
        when(repo.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(repo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = service.findOrCreateByOAuth(
                "github", "999", "  New@Example.com  ", "Newcomer", "https://gh-avatar"
        );

        assertThat(result.getEmail()).isEqualTo("new@example.com");
        assertThat(result.getFullName()).isEqualTo("Newcomer");
        assertThat(result.getOauthProvider()).isEqualTo("github");
        assertThat(result.getOauthSubject()).isEqualTo("999");
        assertThat(result.getPasswordHash()).isNull();
        assertThat(result.getAvatarUrl()).isEqualTo("https://gh-avatar");
        verify(oauthRepo).save(any(OAuthAccount.class));
    }

    @Test
    void linksGithubToExistingGoogleAccountWithoutOverwritingPrimaryProvider() {
        UserRepository repo = mock(UserRepository.class);
        OAuthAccountRepository oauthRepo = oauthRepo();
        UserService service = new UserService(repo, oauthRepo);

        User googleUser = User.builder()
                .id(UUID.randomUUID())
                .fullName("Google User")
                .email("same@example.com")
                .role(UserRole.USER)
                .oauthProvider("google")
                .oauthSubject("google-sub")
                .build();
        when(repo.findByOauthProviderAndOauthSubject("github", "github-sub"))
                .thenReturn(Optional.empty());
        when(repo.findByEmail("same@example.com")).thenReturn(Optional.of(googleUser));
        when(repo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        OAuthTokenDetails token = new OAuthTokenDetails(
                "gho_token",
                "Bearer",
                "read:user user:email repo",
                Instant.parse("2026-01-01T00:00:00Z")
        );

        User result = service.findOrCreateByOAuth(
                "github", "github-sub", "same@example.com", "GitHub Name", "https://avatar", token
        );

        assertThat(result).isSameAs(googleUser);
        assertThat(googleUser.getOauthProvider()).isEqualTo("google");
        assertThat(googleUser.getOauthSubject()).isEqualTo("google-sub");

        verify(oauthRepo).save(org.mockito.ArgumentMatchers.argThat(account ->
                account.getUser() == googleUser &&
                account.getProvider().equals("github") &&
                account.getSubject().equals("github-sub") &&
                account.getAccessToken().equals("gho_token")
        ));
    }

    @Test
    void linksGithubToExistingPasswordAccountWithSameEmail() {
        UserRepository repo = mock(UserRepository.class);
        OAuthAccountRepository oauthRepo = oauthRepo();
        UserService service = new UserService(repo, oauthRepo);

        User passwordUser = User.builder()
                .id(UUID.randomUUID())
                .fullName("Pwd User")
                .email("victim@example.com")
                .passwordHash("$2a$10$existinghash")
                .role(UserRole.USER)
                .build();
        when(repo.findByOauthProviderAndOauthSubject(any(), any())).thenReturn(Optional.empty());
        when(repo.findByEmail("victim@example.com")).thenReturn(Optional.of(passwordUser));
        when(repo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = service.findOrCreateByOAuth(
                "github", "github-sub", "victim@example.com", "GitHub Name", "https://avatar"
        );

        assertThat(result).isSameAs(passwordUser);
        assertThat(passwordUser.getPasswordHash()).isEqualTo("$2a$10$existinghash");
        assertThat(passwordUser.getOauthProvider()).isNull();
        assertThat(passwordUser.getOauthSubject()).isNull();
        assertThat(passwordUser.getFullName()).isEqualTo("GitHub Name");
        assertThat(passwordUser.getAvatarUrl()).isEqualTo("https://avatar");
        verify(oauthRepo).save(org.mockito.ArgumentMatchers.argThat(account ->
                account.getUser() == passwordUser &&
                account.getProvider().equals("github") &&
                account.getSubject().equals("github-sub")
        ));
    }

    @Test
    void rejectsBlankProviderOrSubject() {
        UserRepository repo = mock(UserRepository.class);
        OAuthAccountRepository oauthRepo = oauthRepo();
        // findByOauthProviderAndOauthSubject is never reached when the guard
        // throws — keep stubbing lenient so Mockito does not flag it.
        lenient().when(repo.findByOauthProviderAndOauthSubject(any(), any()))
                .thenReturn(Optional.empty());
        UserService service = new UserService(repo, oauthRepo);

        assertThatThrownBy(() -> service.findOrCreateByOAuth("", "sub", "u@x.com", "n", null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.findOrCreateByOAuth("github", "  ", "u@x.com", "n", null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsCreationWhenProviderEmailIsMissing() {
        UserRepository repo = mock(UserRepository.class);
        OAuthAccountRepository oauthRepo = oauthRepo();
        UserService service = new UserService(repo, oauthRepo);

        when(repo.findByOauthProviderAndOauthSubject(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findOrCreateByOAuth("github", "55", null, "Name", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("did not return an email");
    }

    private static OAuthAccountRepository oauthRepo() {
        OAuthAccountRepository oauthRepo = mock(OAuthAccountRepository.class);
        lenient().when(oauthRepo.findByProviderAndSubject(any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(oauthRepo.findByUser_IdAndProvider(any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(oauthRepo.save(any(OAuthAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        return oauthRepo;
    }
}
