package com.codeleon.admin;

import com.codeleon.user.User;
import com.codeleon.user.UserRepository;
import com.codeleon.user.UserRole;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Promotes a single bootstrap account to {@link UserRole#ADMIN} on startup.
 *
 * <p>The email is read from {@code codeleon.admin.bootstrap-email} (which
 * binds to the {@code CODELEON_BOOTSTRAP_ADMIN_EMAIL} env var). When the
 * value is empty (default) the runner is a no-op, so a fresh checkout
 * boots without surprising privilege escalation.</p>
 *
 * <p>Idempotent: if the matching user is already ADMIN we skip the save.
 * If the user does not yet exist (e.g. they have not signed up yet), the
 * runner logs a debug line and waits for a future restart.</p>
 */
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository userRepository;

    @Value("${codeleon.admin.bootstrap-email:}")
    private String bootstrapEmail;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (bootstrapEmail == null || bootstrapEmail.isBlank()) {
            return;
        }
        String normalized = bootstrapEmail.trim().toLowerCase();
        userRepository.findByEmail(normalized).ifPresentOrElse(
                this::promoteIfNeeded,
                () -> log.debug("Bootstrap admin email {} not found yet; will retry on next start", normalized)
        );
    }

    private void promoteIfNeeded(User user) {
        if (user.getRole() == UserRole.ADMIN) {
            log.debug("Bootstrap admin {} is already ADMIN", user.getEmail());
            return;
        }
        user.setRole(UserRole.ADMIN);
        userRepository.save(user);
        log.info("Promoted {} to ADMIN via bootstrap configuration", user.getEmail());
    }
}
