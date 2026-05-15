package com.codeleon.ai.history;

import com.codeleon.user.User;
import com.codeleon.user.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Privacy + scoping coverage for the AI chat history endpoint.
 *
 * Skips the real chat-streaming flow (no Ollama in CI): we drive
 * persistence directly through {@link RoomChatHistoryService#record}
 * and assert what the GET endpoint returns. This is what we actually
 * care about — that one user never sees another user's thread.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoomChatHistoryService historyService;

    @Autowired
    private UserService userService;

    @Test
    void callerSeesOnlyTheirOwnMessagesInOrder() throws Exception {
        String ownerToken = register("history.owner.self@example.com", "Owner Self");
        JsonNode room = createRoom(ownerToken, "Self History Room");
        UUID roomId = UUID.fromString(room.get("id").asText());

        User owner = loadUser("history.owner.self@example.com");
        historyService.record(roomId, owner, RoomChatRole.USER, "what is fibonacci?");
        historyService.record(roomId, owner, RoomChatRole.ASSISTANT, "a sequence where each number is the sum of the previous two");

        mockMvc.perform(get("/rooms/" + roomId + "/chat/history")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[0].content").value("what is fibonacci?"))
                .andExpect(jsonPath("$[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$[1].userName").value("Owner Self"));
    }

    @Test
    void invitedMemberCannotSeeAnotherMembersChat() throws Exception {
        String ownerToken = register("history.owner.privacy@example.com", "Owner P");
        String guestToken = register("history.guest.privacy@example.com", "Guest P");

        JsonNode room = createRoom(ownerToken, "Privacy Room");
        UUID roomId = UUID.fromString(room.get("id").asText());
        String inviteCode = room.get("inviteCode").asText();

        // Guest joins so canRead passes.
        mockMvc.perform(post("/rooms/join/" + inviteCode)
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk());

        User owner = loadUser("history.owner.privacy@example.com");
        User guest = loadUser("history.guest.privacy@example.com");

        // Both have written messages in the same room.
        historyService.record(roomId, owner, RoomChatRole.USER, "owner secret question");
        historyService.record(roomId, owner, RoomChatRole.ASSISTANT, "owner secret reply");
        historyService.record(roomId, guest, RoomChatRole.USER, "guest question");
        historyService.record(roomId, guest, RoomChatRole.ASSISTANT, "guest reply");

        // The guest endpoint returns ONLY the guest's two messages.
        mockMvc.perform(get("/rooms/" + roomId + "/chat/history")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].content").value("guest question"))
                .andExpect(jsonPath("$[1].content").value("guest reply"));

        // And the owner endpoint, at this AI-3a stage, also returns ONLY the
        // owner's two messages — the owner-review override lands in AI-3b.
        mockMvc.perform(get("/rooms/" + roomId + "/chat/history")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].content").value("owner secret question"));
    }

    @Test
    void historyIsScopedToTheRoom() throws Exception {
        String token = register("history.scoping@example.com", "Scoper");
        JsonNode room1 = createRoom(token, "Room One");
        JsonNode room2 = createRoom(token, "Room Two");
        UUID room1Id = UUID.fromString(room1.get("id").asText());
        UUID room2Id = UUID.fromString(room2.get("id").asText());

        User user = loadUser("history.scoping@example.com");
        historyService.record(room1Id, user, RoomChatRole.USER, "message in room 1");
        historyService.record(room2Id, user, RoomChatRole.USER, "message in room 2");

        mockMvc.perform(get("/rooms/" + room1Id + "/chat/history")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("message in room 1"));
    }

    @Test
    void nonMemberOfPrivateRoomCannotSeeHistory() throws Exception {
        String ownerToken = register("history.outside.owner@example.com", "Out Owner");
        String outsiderToken = register("history.outside.stranger@example.com", "Stranger");
        JsonNode room = createRoom(ownerToken, "Closed Room");
        UUID roomId = UUID.fromString(room.get("id").asText());

        mockMvc.perform(get("/rooms/" + roomId + "/chat/history")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
    }

    // ---- helpers ----

    private User loadUser(String email) {
        return (User) userService.loadUserByUsername(email);
    }

    private String register(String email, String fullName) throws Exception {
        String response = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", fullName,
                                "email", email,
                                "password", "Password123"
                        ))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private JsonNode createRoom(String token, String name) throws Exception {
        String response = mockMvc.perform(post("/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "visibility", "PRIVATE"
                        ))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }
}
