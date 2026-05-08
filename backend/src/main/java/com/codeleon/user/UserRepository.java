package com.codeleon.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByOauthProviderAndOauthSubject(String oauthProvider, String oauthSubject);

    java.util.List<User> findAllByOrderByCreatedAtDesc();

    long countByRole(com.codeleon.user.UserRole role);

    long countByOauthProvider(String oauthProvider);

    long countByOauthProviderIsNull();

    long countByCreatedAtAfter(java.time.Instant cutoff);
}
