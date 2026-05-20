package com.codeleon.auth;

import com.codeleon.auth.oauth.OAuthAccount;
import com.codeleon.auth.oauth.OAuthAccountRepository;
import com.codeleon.user.User;
import com.codeleon.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OAuthAccountRepository oauthAccountRepository;

    @Test
    void registerCreatesUserAndReturnsTokens() throws Exception {
        String response = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", "Badr Ziani",
                                "email", "badr.register@example.com",
                                "password", "Password123"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.user.email").value("badr.register@example.com"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("accessToken").asText()).isNotBlank();
    }

    @Test
    void loginReturnsTokensForExistingUser() throws Exception {
        register("badr.login@example.com");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "badr.login@example.com",
                                "password", "Password123"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.user.fullName").value("Badr Ziani"));
    }

    @Test
    void meReturnsAuthenticatedUser() throws Exception {
        String accessToken = register("badr.me@example.com").get("accessToken").asText();

        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("badr.me@example.com"));
    }

    @Test
    void oauthAccountsReturnsLinkedProvidersWithoutTokens() throws Exception {
        String accessToken = register("badr.links@example.com").get("accessToken").asText();
        User user = userRepository.findByEmail("badr.links@example.com").orElseThrow();
        OAuthAccount account = OAuthAccount.builder()
                .user(user)
                .provider("github")
                .subject("12345")
                .email("badr@github.example")
                .accessToken("secret-token")
                .tokenType("Bearer")
                .scopes("read:user,user:email,repo")
                .expiresAt(Instant.parse("2030-01-01T00:00:00Z"))
                .build();
        oauthAccountRepository.save(account);

        mockMvc.perform(get("/users/me/oauth-accounts")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].provider").value("github"))
                .andExpect(jsonPath("$[0].email").value("badr@github.example"))
                .andExpect(jsonPath("$[0].scopes").value("read:user,user:email,repo"))
                .andExpect(jsonPath("$[0].expiresAt").value("2030-01-01T00:00:00Z"))
                .andExpect(jsonPath("$[0].accessToken").doesNotExist());
    }

    private JsonNode register(String email) throws Exception {
        String response = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", "Badr Ziani",
                                "email", email,
                                "password", "Password123"
                        ))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }
}
