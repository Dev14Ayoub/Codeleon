package com.codeleon.user;

import com.codeleon.common.exception.BadRequestException;
import com.codeleon.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    public User getById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    /**
     * Resolves the local {@link User} for an OAuth2 sign-in, creating one if
     * the {@code (provider, subject)} pair is not yet known.
     *
     * <p>If a user with the same email already exists with a password,
     * we deliberately refuse to silently link the OAuth identity — that
     * would let anyone who controls a matching email at the provider
     * take over an existing password account. The user must log in via
     * the original method first and link from a settings screen (a
     * follow-up).</p>
     */
    @Transactional
    public User findOrCreateByOAuth(
            String provider,
            String subject,
            String email,
            String fullName,
            String avatarUrl
    ) {
        if (provider == null || provider.isBlank() || subject == null || subject.isBlank()) {
            throw new BadRequestException("OAuth provider and subject are required");
        }

        return userRepository.findByOauthProviderAndOauthSubject(provider, subject)
                .map(existing -> updateProfile(existing, fullName, avatarUrl))
                .orElseGet(() -> linkOrCreate(provider, subject, email, fullName, avatarUrl));
    }

    private User linkOrCreate(
            String provider,
            String subject,
            String email,
            String fullName,
            String avatarUrl
    ) {
        if (email == null || email.isBlank()) {
            // Defensive — both providers always return an email. If we
            // hit this branch we'd produce a stub username we cannot
            // reach for password recovery.
            throw new BadRequestException(
                    "OAuth provider " + provider + " did not return an email address"
            );
        }
        String normalizedEmail = email.trim().toLowerCase();

        return userRepository.findByEmail(normalizedEmail)
                .map(existing -> {
                    if (existing.getPasswordHash() != null) {
                        throw new BadRequestException(
                                "An account already exists for " + normalizedEmail +
                                ". Sign in with your password first to link your " + provider + " account."
                        );
                    }
                    // Existing OAuth account that lost its provider link
                    // for some reason — restore it.
                    existing.setOauthProvider(provider);
                    existing.setOauthSubject(subject);
                    return updateProfile(existing, fullName, avatarUrl);
                })
                .orElseGet(() -> userRepository.save(User.builder()
                        .fullName(displayName(fullName, normalizedEmail))
                        .email(normalizedEmail)
                        .passwordHash(null)
                        .role(UserRole.USER)
                        .oauthProvider(provider)
                        .oauthSubject(subject)
                        .avatarUrl(avatarUrl)
                        .build()));
    }

    private User updateProfile(User user, String fullName, String avatarUrl) {
        boolean dirty = false;
        if (fullName != null && !fullName.isBlank() && !fullName.equals(user.getFullName())) {
            user.setFullName(fullName);
            dirty = true;
        }
        if (avatarUrl != null && !avatarUrl.equals(user.getAvatarUrl())) {
            user.setAvatarUrl(avatarUrl);
            dirty = true;
        }
        return dirty ? userRepository.save(user) : user;
    }

    private static String displayName(String fullName, String email) {
        if (fullName != null && !fullName.isBlank()) return fullName.trim();
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
