package com.codeleon.user;

import com.codeleon.auth.oauth.OAuthAccount;
import com.codeleon.auth.oauth.OAuthAccountRepository;
import com.codeleon.auth.oauth.OAuthTokenDetails;
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
    private final OAuthAccountRepository oauthAccountRepository;

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
     * <p>If a user with the same email already exists, we attach the
     * provider identity to that Codeleon account. This keeps the dashboard
     * "Connect GitHub" flow usable even though the browser stores the
     * Codeleon JWT in localStorage rather than a backend session cookie.</p>
     */
    @Transactional
    public User findOrCreateByOAuth(
            String provider,
            String subject,
            String email,
            String fullName,
            String avatarUrl
    ) {
        return findOrCreateByOAuth(provider, subject, email, true, fullName, avatarUrl, null);
    }

    @Transactional
    public User findOrCreateByOAuth(
            String provider,
            String subject,
            String email,
            String fullName,
            String avatarUrl,
            OAuthTokenDetails token
    ) {
        return findOrCreateByOAuth(provider, subject, email, true, fullName, avatarUrl, token);
    }

    /**
     * Canonical entry point. {@code emailVerified} reflects whether the
     * provider asserts the user actually controls {@code email} (Google's
     * {@code email_verified} OIDC claim; GitHub primary/public emails are
     * verified by policy). It guards the email-matching branch in
     * {@link #linkOrCreate}: an unverified email must never attach this
     * external identity to a pre-existing Codeleon account, which would be an
     * account-takeover vector. Re-authentication of an already-linked
     * provider+subject is unaffected — that identity is already trusted.
     */
    @Transactional
    public User findOrCreateByOAuth(
            String provider,
            String subject,
            String email,
            boolean emailVerified,
            String fullName,
            String avatarUrl,
            OAuthTokenDetails token
    ) {
        if (provider == null || provider.isBlank() || subject == null || subject.isBlank()) {
            throw new BadRequestException("OAuth provider and subject are required");
        }

        String normalizedProvider = provider.trim().toLowerCase();
        String normalizedSubject = subject.trim();

        return oauthAccountRepository.findByProviderAndSubject(normalizedProvider, normalizedSubject)
                .map(account -> {
                    updateLinkedAccount(account, email, token);
                    return updateProfile(account.getUser(), fullName, avatarUrl);
                })
                .or(() -> userRepository.findByOauthProviderAndOauthSubject(normalizedProvider, normalizedSubject)
                        .map(existing -> {
                            ensureLinkedAccount(existing, normalizedProvider, normalizedSubject, email, token);
                            return updateProfile(existing, fullName, avatarUrl);
                        }))
                .orElseGet(() -> linkOrCreate(
                        normalizedProvider, normalizedSubject, email, emailVerified, fullName, avatarUrl, token));
    }

    private User linkOrCreate(
            String provider,
            String subject,
            String email,
            boolean emailVerified,
            String fullName,
            String avatarUrl,
            OAuthTokenDetails token
    ) {
        if (email == null || email.isBlank()) {
            // Defensive — both providers always return an email. If we
            // hit this branch we'd produce a stub username we cannot
            // reach for password recovery.
            throw new BadRequestException(
                    "OAuth provider " + provider + " did not return an email address"
            );
        }
        // Account-takeover / squatting guard: a first-time OAuth identity must
        // not be linked to (or create) a Codeleon account on an email the
        // provider has not verified the user controls. Re-authentication of an
        // already-linked provider+subject never reaches here, so this only
        // gates the very first login. Google consumer emails are always
        // verified; this blocks only genuinely unproven addresses.
        if (!emailVerified) {
            throw new BadRequestException(
                    "Your " + provider + " email address is not verified; verify it with "
                            + provider + " and sign in again"
            );
        }
        String normalizedEmail = email.trim().toLowerCase();

        return userRepository.findByEmail(normalizedEmail)
                .map(existing -> {
                    if (existing.getPasswordHash() == null
                            && (existing.getOauthProvider() == null || existing.getOauthProvider().isBlank())) {
                        existing.setOauthProvider(provider);
                        existing.setOauthSubject(subject);
                    }
                    ensureLinkedAccount(existing, provider, subject, normalizedEmail, token);
                    return updateProfile(existing, fullName, avatarUrl);
                })
                .orElseGet(() -> {
                    User created = userRepository.save(User.builder()
                        .fullName(displayName(fullName, normalizedEmail))
                        .email(normalizedEmail)
                        .passwordHash(null)
                        .role(UserRole.USER)
                        .oauthProvider(provider)
                        .oauthSubject(subject)
                        .avatarUrl(avatarUrl)
                        .build());
                    ensureLinkedAccount(created, provider, subject, normalizedEmail, token);
                    return created;
                });
    }

    private OAuthAccount ensureLinkedAccount(
            User user,
            String provider,
            String subject,
            String email,
            OAuthTokenDetails token
    ) {
        return oauthAccountRepository.findByUser_IdAndProvider(user.getId(), provider)
                .map(existing -> {
                    if (!existing.getSubject().equals(subject)) {
                        throw new BadRequestException(
                                "A different " + provider + " account is already linked to this Codeleon account"
                        );
                    }
                    updateLinkedAccount(existing, email, token);
                    return existing;
                })
                .orElseGet(() -> oauthAccountRepository.save(OAuthAccount.builder()
                        .user(user)
                        .provider(provider)
                        .subject(subject)
                        .email(normalizedEmailOrNull(email))
                        .accessToken(token == null ? null : token.accessToken())
                        .tokenType(token == null ? null : token.tokenType())
                        .scopes(token == null ? null : token.scopes())
                        .expiresAt(token == null ? null : token.expiresAt())
                        .build()));
    }

    private void updateLinkedAccount(OAuthAccount account, String email, OAuthTokenDetails token) {
        account.setEmail(normalizedEmailOrNull(email));
        if (token != null) {
            account.setAccessToken(token.accessToken());
            account.setTokenType(token.tokenType());
            account.setScopes(token.scopes());
            account.setExpiresAt(token.expiresAt());
        }
        oauthAccountRepository.save(account);
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

    private static String normalizedEmailOrNull(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase();
    }
}
