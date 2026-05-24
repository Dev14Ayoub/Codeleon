package fake.auth;

import fake.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Issues, validates, and refreshes session tokens. Tokens expire after
 * one hour by default; refresh trades the old token for a fresh one
 * without forcing the user to re-enter their password.
 */
public class AuthService {

    private static final Duration TOKEN_TTL = Duration.ofHours(1);

    private final UserRepository users;
    private final TokenStore tokens;

    public AuthService(UserRepository users, TokenStore tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    public String login(String email, String password) {
        var user = users.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("unknown user"));
        if (!user.verifyPassword(password)) {
            throw new IllegalArgumentException("bad password");
        }
        return tokens.issue(user.id(), TOKEN_TTL);
    }

    public String refreshToken(String oldToken) {
        UUID userId = tokens.resolve(oldToken)
                .orElseThrow(() -> new IllegalStateException("token invalid or expired"));
        tokens.revoke(oldToken);
        return tokens.issue(userId, TOKEN_TTL);
    }

    public void logout(String token) {
        tokens.revoke(token);
    }

    public boolean isExpired(String token) {
        return tokens.expiresAt(token)
                .map(at -> at.isBefore(Instant.now()))
                .orElse(true);
    }
}
