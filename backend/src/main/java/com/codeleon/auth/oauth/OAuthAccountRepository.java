package com.codeleon.auth.oauth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, UUID> {

    Optional<OAuthAccount> findByProviderAndSubject(String provider, String subject);

    Optional<OAuthAccount> findByUser_IdAndProvider(UUID userId, String provider);

    List<OAuthAccount> findByUser_IdOrderByProviderAsc(UUID userId);
}
