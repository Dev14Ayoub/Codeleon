package com.codeleon.user;

import com.codeleon.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

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
        UserService service = new UserService(repo);

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
        UserService service = new UserService(repo);

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
        UserService service = new UserService(repo);

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
    }

    @Test
    void refusesToHijackPasswordAccountWithSameEmail() {
        UserRepository repo = mock(UserRepository.class);
        UserService service = new UserService(repo);

        User passwordUser = User.builder()
                .fullName("Pwd User")
                .email("victim@example.com")
                .passwordHash("$2a$10$existinghash")
                .role(UserRole.USER)
                .build();
        when(repo.findByOauthProviderAndOauthSubject(any(), any())).thenReturn(Optional.empty());
        when(repo.findByEmail("victim@example.com")).thenReturn(Optional.of(passwordUser));

        assertThatThrownBy(() -> service.findOrCreateByOAuth(
                "github", "evil-sub", "victim@example.com", "Attacker", null
        )).isInstanceOf(BadRequestException.class)
          .hasMessageContaining("Sign in with your password first");

        verify(repo, never()).save(any(User.class));
    }

    @Test
    void rejectsBlankProviderOrSubject() {
        UserRepository repo = mock(UserRepository.class);
        // findByOauthProviderAndOauthSubject is never reached when the guard
        // throws — keep stubbing lenient so Mockito does not flag it.
        lenient().when(repo.findByOauthProviderAndOauthSubject(any(), any()))
                .thenReturn(Optional.empty());
        UserService service = new UserService(repo);

        assertThatThrownBy(() -> service.findOrCreateByOAuth("", "sub", "u@x.com", "n", null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.findOrCreateByOAuth("github", "  ", "u@x.com", "n", null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsCreationWhenProviderEmailIsMissing() {
        UserRepository repo = mock(UserRepository.class);
        UserService service = new UserService(repo);

        when(repo.findByOauthProviderAndOauthSubject(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findOrCreateByOAuth("github", "55", null, "Name", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("did not return an email");
    }
}
