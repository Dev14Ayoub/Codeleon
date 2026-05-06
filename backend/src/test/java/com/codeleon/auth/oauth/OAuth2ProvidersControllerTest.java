package com.codeleon.auth.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuth2ProvidersControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @SuppressWarnings("unused")
    private ObjectMapper objectMapper;

    @Test
    void providersListIsEmptyWhenNoOAuthCredentialsAreSet() throws Exception {
        // application-test.yml does not configure github/google client-ids,
        // so OAuth2ClientConfig's @ConditionalOnExpression skips the bean
        // and the providers endpoint should report an empty list. The
        // endpoint must remain reachable without a JWT (it is permitted in
        // SecurityConfig so the login page can fetch it).
        mockMvc.perform(get("/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers").isArray())
                .andExpect(jsonPath("$.providers.length()").value(0));
    }
}
