package com.codeleon.auth.oauth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Tells the frontend which OAuth2 social providers are actually configured
 * on this backend so it can render only the relevant buttons.
 *
 * <p>Public endpoint by design — no JWT required, but the response contains
 * nothing more sensitive than the registration IDs of providers we have
 * client credentials for.</p>
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class OAuth2ProvidersController {

    private final ObjectProvider<ClientRegistrationRepository> clientRegistrationRepositoryProvider;

    @GetMapping("/providers")
    public ProvidersResponse listProviders() {
        ClientRegistrationRepository repo = clientRegistrationRepositoryProvider.getIfAvailable();
        List<String> providers = new ArrayList<>();
        if (repo instanceof InMemoryClientRegistrationRepository inMemory) {
            for (ClientRegistration registration : inMemory) {
                providers.add(registration.getRegistrationId());
            }
        }
        return new ProvidersResponse(providers);
    }

    public record ProvidersResponse(List<String> providers) {
    }
}
