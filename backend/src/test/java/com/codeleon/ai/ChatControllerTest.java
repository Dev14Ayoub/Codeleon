package com.codeleon.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import org.mockito.stubbing.Answer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "codeleon.ai.enabled=true")
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RoomChatService chatService;

    @MockBean
    private OllamaClient ollamaClient;

    @MockBean
    private OllamaStreamer ollamaStreamer;

    @MockBean
    private QdrantClient qdrantClient;

    @MockBean
    private RoomFileIndexer indexer;

    @Test
    void chatStartsAsyncStreamForRoomMember() throws Exception {
        String token = register("chat.owner@example.com");
        JsonNode room = createRoom(token, "Chat Room");
        String roomId = room.get("id").asText();

        // Stub the chat service to complete the emitter immediately so the
        // CompletableFuture inside the controller finishes cleanly even though
        // we don't drain the async dispatch (which would re-enter Spring
        // Security filters without the JWT in a way that returns 403).
        doAnswer((Answer<Void>) invocation -> {
            SseEmitter emitter = invocation.getArgument(2);
            emitter.complete();
            return null;
        }).when(chatService).streamChat(any(), any(), any());

        // Asserting asyncStarted() is enough: it proves auth passed, the AI
        // flag check passed, and the controller returned an SseEmitter which
        // is what triggers Spring MVC's async machinery in the first place.
        mockMvc.perform(post("/rooms/" + roomId + "/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "query", "what does this code do?"
                        ))))
                .andExpect(request().asyncStarted());
    }

    @Test
    void chatRejectsNonMemberOfPrivateRoom() throws Exception {
        String ownerToken = register("chat.owner.private@example.com");
        String outsiderToken = register("chat.outsider@example.com");
        JsonNode room = createRoom(ownerToken, "Private Chat Room");
        String roomId = room.get("id").asText();

        mockMvc.perform(post("/rooms/" + roomId + "/chat")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "query", "tell me secrets"
                        ))))
                .andExpect(status().isNotFound());
    }

    private String register(String email) throws Exception {
        String response = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", "Chat Tester",
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
                                "description", "Created by tests",
                                "visibility", "PRIVATE"
                        ))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }
}
