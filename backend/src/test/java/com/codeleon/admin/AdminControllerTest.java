package com.codeleon.admin;

import com.codeleon.user.User;
import com.codeleon.user.UserRepository;
import com.codeleon.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    // ---------------------------------------------------------------
    // Role guard
    // ---------------------------------------------------------------

    @Test
    void usersListRejectsRegularUserWith403() throws Exception {
        String regularToken = register("admin.regular@example.com");
        mockMvc.perform(get("/admin/users")
                        .header("Authorization", "Bearer " + regularToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void usersListRejectsAnonymous() throws Exception {
        // Spring Security with OAuth2 login on the chain forwards
        // unauthenticated traffic through the authorization filter
        // before the entry point, so the response is 403 Forbidden
        // rather than the more typical 401 Unauthorized. Either is a
        // valid rejection — we just assert the request did not succeed.
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().is4xxClientError());
    }

    // ---------------------------------------------------------------
    // Happy path: admin can list and inspect
    // ---------------------------------------------------------------

    @Test
    void adminCanListAndInspectUsers() throws Exception {
        String adminToken = registerAdmin("admin.list@example.com");
        register("regular.list1@example.com");
        register("regular.list2@example.com");

        mockMvc.perform(get("/admin/users")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));

        // Stats endpoint
        mockMvc.perform(get("/admin/stats")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").isNumber())
                .andExpect(jsonPath("$.usersByRole.ADMIN").isNumber())
                .andExpect(jsonPath("$.usersByRole.USER").isNumber())
                .andExpect(jsonPath("$.usersByAuthMethod.password").isNumber());
    }

    // ---------------------------------------------------------------
    // Role changes: promote / demote with safeguards
    // ---------------------------------------------------------------

    @Test
    void adminCanPromoteAnotherUser() throws Exception {
        String adminToken = registerAdmin("admin.promote@example.com");
        String regularResponse = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", "To Promote",
                                "email", "topromote@example.com",
                                "password", "Password123"
                        ))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String userId = objectMapper.readTree(regularResponse).get("user").get("id").asText();

        mockMvc.perform(patch("/admin/users/" + userId + "/role")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void adminCannotDemoteSelf() throws Exception {
        String adminToken = registerAdmin("admin.selfdemote@example.com");
        String selfId = objectMapper.readTree(getMe(adminToken)).get("id").asText();

        mockMvc.perform(patch("/admin/users/" + selfId + "/role")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "USER"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCannotDeleteSelf() throws Exception {
        String adminToken = registerAdmin("admin.selfdelete@example.com");
        String selfId = objectMapper.readTree(getMe(adminToken)).get("id").asText();

        mockMvc.perform(delete("/admin/users/" + selfId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCanDeleteAnotherUser() throws Exception {
        String adminToken = registerAdmin("admin.delete@example.com");
        String otherResponse = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", "To Delete",
                                "email", "todelete@example.com",
                                "password", "Password123"
                        ))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String userId = objectMapper.readTree(otherResponse).get("user").get("id").asText();

        mockMvc.perform(delete("/admin/users/" + userId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private String register(String email) throws Exception {
        String response = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", "Admin Tester",
                                "email", email,
                                "password", "Password123"
                        ))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    /**
     * Registers a user via the public auth endpoint, then promotes them to
     * ADMIN directly through the repository (the only way to bootstrap an
     * admin in tests since /admin endpoints already require ADMIN). Returns
     * a JWT carrying the new role — that means we have to re-issue the
     * token by hitting /auth/login again.
     */
    @Transactional
    String registerAdmin(String email) throws Exception {
        register(email);
        User user = userRepository.findByEmail(email).orElseThrow();
        user.setRole(UserRole.ADMIN);
        userRepository.save(user);

        String response = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", "Password123"
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private String getMe(String token) throws Exception {
        return mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @SuppressWarnings("unused")
    private JsonNode parse(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
