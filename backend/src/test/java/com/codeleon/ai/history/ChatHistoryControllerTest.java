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

        // /chat/history with no userId returns the caller's own thread,
        // even for the owner — the AI-3b owner-override is opt-in via the
        // ?userId param, not implicit on the default endpoint.
        mockMvc.perform(get("/rooms/" + roomId + "/chat/history")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].content").value("owner secret question"));
    }

    @Test
    void ownerCanReadAnotherMembersThreadViaUserIdParam() throws Exception {
        String ownerToken = register("history.review.owner@example.com", "Review Owner");
        String guestToken = register("history.review.guest@example.com", "Review Guest");

        JsonNode room = createRoom(ownerToken, "Review Room");
        UUID roomId = UUID.fromString(room.get("id").asText());
        String inviteCode = room.get("inviteCode").asText();

        mockMvc.perform(post("/rooms/join/" + inviteCode)
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk());

        User guest = loadUser("history.review.guest@example.com");
        historyService.record(roomId, guest, RoomChatRole.USER, "i wrote a bug");
        historyService.record(roomId, guest, RoomChatRole.ASSISTANT, "here is the fix");

        // Owner fetches the guest's thread by id — gets the guest's two
        // messages, with the guest tagged as the author.
        mockMvc.perform(get("/rooms/" + roomId + "/chat/history?userId=" + guest.getId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].content").value("i wrote a bug"))
                .andExpect(jsonPath("$[0].userName").value("Review Guest"))
                .andExpect(jsonPath("$[1].content").value("here is the fix"));
    }

    @Test
    void nonOwnerCannotReadAnotherMembersThread() throws Exception {
        String ownerToken = register("history.guard.owner@example.com", "Guard Owner");
        String aliceToken = register("history.guard.alice@example.com", "Alice");
        String bobToken = register("history.guard.bob@example.com", "Bob");

        JsonNode room = createRoom(ownerToken, "Guard Room");
        UUID roomId = UUID.fromString(room.get("id").asText());
        String inviteCode = room.get("inviteCode").asText();

        for (String t : new String[]{aliceToken, bobToken}) {
            mockMvc.perform(post("/rooms/join/" + inviteCode)
                            .header("Authorization", "Bearer " + t))
                    .andExpect(status().isOk());
        }

        User bob = loadUser("history.guard.bob@example.com");
        historyService.record(roomId, bob, RoomChatRole.USER, "bob writes");

        // Alice tries to peek at Bob's thread — must be 403. The owner can
        // also see it (covered above) but a co-member cannot.
        mockMvc.perform(get("/rooms/" + roomId + "/chat/history?userId=" + bob.getId())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerCanListAllThreadsInRoom() throws Exception {
        String ownerToken = register("history.threads.owner@example.com", "Threads Owner");
        String aliceToken = register("history.threads.alice@example.com", "Alice T");
        String bobToken = register("history.threads.bob@example.com", "Bob T");

        JsonNode room = createRoom(ownerToken, "Threads Room");
        UUID roomId = UUID.fromString(room.get("id").asText());
        String inviteCode = room.get("inviteCode").asText();
        for (String t : new String[]{aliceToken, bobToken}) {
            mockMvc.perform(post("/rooms/join/" + inviteCode)
                            .header("Authorization", "Bearer " + t))
                    .andExpect(status().isOk());
        }

        User alice = loadUser("history.threads.alice@example.com");
        User bob = loadUser("history.threads.bob@example.com");
        historyService.record(roomId, alice, RoomChatRole.USER, "alice msg 1");
        historyService.record(roomId, alice, RoomChatRole.ASSISTANT, "alice reply 1");
        historyService.record(roomId, bob, RoomChatRole.USER, "bob msg 1");

        mockMvc.perform(get("/rooms/" + roomId + "/chat/threads")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void nonOwnerCannotListThreads() throws Exception {
        String ownerToken = register("history.threads.guard.owner@example.com", "TG Owner");
        String guestToken = register("history.threads.guard.guest@example.com", "TG Guest");

        JsonNode room = createRoom(ownerToken, "Threads Guard Room");
        UUID roomId = UUID.fromString(room.get("id").asText());
        String inviteCode = room.get("inviteCode").asText();
        mockMvc.perform(post("/rooms/join/" + inviteCode)
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/rooms/" + roomId + "/chat/threads")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isForbidden());
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
