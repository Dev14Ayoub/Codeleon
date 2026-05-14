package com.codeleon.room.event;

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

/**
 * End-to-end coverage for the activity feed: an action emits an event,
 * the cross-room GET /events surfaces it, and the room listing picks up
 * the denormalised lastEditedBy pointer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fileCreationEmitsAnEventVisibleInTheFeed() throws Exception {
        String token = register("events.create@example.com");
        String roomId = createRoom(token, "Feed Room").get("id").asText();

        // Brand-new room: no activity yet.
        mockMvc.perform(get("/events").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Creating a file is an activity-feed-worthy action.
        mockMvc.perform(post("/rooms/" + roomId + "/files")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("path", "App.java"))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/events").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("FILE_CREATED"))
                .andExpect(jsonPath("$[0].roomName").value("Feed Room"))
                .andExpect(jsonPath("$[0].userName").value("Event Tester"))
                .andExpect(jsonPath("$[0].payload.path").value("App.java"));
    }

    @Test
    void feedIsScopedToRoomsTheUserBelongsTo() throws Exception {
        String ownerToken = register("events.owner@example.com");
        String strangerToken = register("events.stranger@example.com");

        String roomId = createRoom(ownerToken, "Private Feed").get("id").asText();
        mockMvc.perform(post("/rooms/" + roomId + "/files")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("path", "secret.py"))))
                .andExpect(status().isCreated());

        // The owner sees the event...
        mockMvc.perform(get("/events").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // ...a stranger who is not a member sees nothing.
        mockMvc.perform(get("/events").header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void joiningARoomEmitsMemberJoinedAndFillsLastEditedBy() throws Exception {
        String ownerToken = register("events.lastedit.owner@example.com");
        String guestToken = register("events.lastedit.guest@example.com");

        JsonNode room = createRoom(ownerToken, "Last Edited Room");
        String roomId = room.get("id").asText();
        String inviteCode = room.get("inviteCode").asText();

        // Fresh room: lastEditedBy is still null on the owner's listing.
        mockMvc.perform(get("/rooms").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lastEditedById").doesNotExist())
                .andExpect(jsonPath("$[0].lastEditedByName").doesNotExist());

        // Guest joins -> MEMBER_JOINED event + lastEditedBy now points at guest.
        mockMvc.perform(post("/rooms/join/" + inviteCode)
                        .header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/events").header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("MEMBER_JOINED"));

        mockMvc.perform(get("/rooms").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lastEditedByName").value("Event Tester"));
    }

    private String register(String email) throws Exception {
        String response = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", "Event Tester",
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
                                "description", "Room created from event tests",
                                "visibility", "PRIVATE"
                        ))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }
}
