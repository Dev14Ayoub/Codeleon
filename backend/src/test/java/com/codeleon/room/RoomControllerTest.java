package com.codeleon.room;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createRoomAddsOwnerMembership() throws Exception {
        String token = register("room.owner@example.com");

        mockMvc.perform(post("/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Algorithms Lab",
                                "description", "Practice data structures",
                                "visibility", "PRIVATE"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Algorithms Lab"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.currentUserRole").value("OWNER"))
                .andExpect(jsonPath("$.inviteCode").isString())
                .andExpect(jsonPath("$.fileCount").value(0))
                .andExpect(jsonPath("$.memberCount").value(1));

        mockMvc.perform(get("/rooms")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Algorithms Lab"))
                .andExpect(jsonPath("$[0].currentUserRole").value("OWNER"))
                .andExpect(jsonPath("$[0].fileCount").value(0))
                .andExpect(jsonPath("$[0].memberCount").value(1));
    }

    @Test
    void roomResponseReflectsFileAndMemberCounts() throws Exception {
        String ownerToken = register("room.counts.owner@example.com");
        String guestToken = register("room.counts.guest@example.com");

        JsonNode room = createRoom(ownerToken, "Counted", "PRIVATE");
        String roomId = room.get("id").asText();
        String inviteCode = room.get("inviteCode").asText();

        mockMvc.perform(post("/rooms/" + roomId + "/files")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("path", "App.java"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/rooms/join/" + inviteCode)
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/rooms/" + roomId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileCount").value(1))
                .andExpect(jsonPath("$.memberCount").value(2));
    }

    @Test
    void publicRoomsReturnsPublicRooms() throws Exception {
        String token = register("room.public@example.com");

        createRoom(token, "Open Spring Room", "PUBLIC");
        createRoom(token, "Private Spring Room", "PRIVATE");

        mockMvc.perform(get("/rooms/public")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Open Spring Room"))
                .andExpect(jsonPath("$[0].visibility").value("PUBLIC"));
    }

    @Test
    void joinByInviteCodeAddsEditorMembership() throws Exception {
        String ownerToken = register("room.owner.join@example.com");
        String guestToken = register("room.guest.join@example.com");
        JsonNode room = createRoom(ownerToken, "Pair Programming", "PRIVATE");
        String inviteCode = room.get("inviteCode").asText();

        mockMvc.perform(post("/rooms/join/" + inviteCode)
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pair Programming"))
                .andExpect(jsonPath("$.currentUserRole").value("EDITOR"));

        mockMvc.perform(get("/rooms")
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Pair Programming"))
                .andExpect(jsonPath("$[0].currentUserRole").value("EDITOR"));
    }

    private String register(String email) throws Exception {
        String response = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", "Room Tester",
                                "email", email,
                                "password", "Password123"
                        ))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private JsonNode createRoom(String token, String name, String visibility) throws Exception {
        String response = mockMvc.perform(post("/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "description", "Room created from tests",
                                "visibility", visibility
                        ))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }
}
