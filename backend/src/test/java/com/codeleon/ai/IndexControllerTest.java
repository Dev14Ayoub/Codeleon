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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "codeleon.ai.enabled=true")
class IndexControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RoomFileIndexer indexer;

    @MockBean
    private OllamaClient ollamaClient;

    @MockBean
    private QdrantClient qdrantClient;

    @Test
    void indexInvokesIndexerForRoomMember() throws Exception {
        String token = register("indexer.owner@example.com");
        JsonNode room = createRoom(token, "Indexer Room");
        String roomId = room.get("id").asText();

        when(indexer.index(any(), eq("main"), eq("System.out.println(\"hi\");")))
                .thenReturn(new IndexResult(1, 17L));

        mockMvc.perform(post("/rooms/" + roomId + "/index")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "path", "main",
                                "text", "System.out.println(\"hi\");"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunks").value(1))
                .andExpect(jsonPath("$.durationMs").value(17));
    }

    @Test
    void indexRejectsNonMember() throws Exception {
        String ownerToken = register("indexer.owner.private@example.com");
        String outsiderToken = register("indexer.outsider@example.com");
        JsonNode room = createRoom(ownerToken, "Private Indexer Room");
        String roomId = room.get("id").asText();

        mockMvc.perform(post("/rooms/" + roomId + "/index")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "path", "main",
                                "text", "x"
                        ))))
                .andExpect(status().isNotFound());

        verify(indexer, never()).index(any(), any(), any());
    }

    private String register(String email) throws Exception {
        String response = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", "Indexer Tester",
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
