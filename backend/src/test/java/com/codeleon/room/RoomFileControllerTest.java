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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomFileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void freshRoomExposesADefaultFileAfterFirstSnapshotFetch() throws Exception {
        String token = register("files.owner@example.com");
        String roomId = createRoom(token, "Fresh Files").get("id").asText();

        // Touch the snapshot endpoint so the service auto-creates the default file.
        mockMvc.perform(get("/rooms/" + roomId + "/snapshot")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/rooms/" + roomId + "/files")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].path").value("main"))
                .andExpect(jsonPath("$[0].language").value("plaintext"));
    }

    @Test
    void createsRenamesAndDeletesFiles() throws Exception {
        String token = register("files.crud@example.com");
        String roomId = createRoom(token, "CRUD Files").get("id").asText();

        // Create App.java — language auto-detected from extension.
        String createResp = mockMvc.perform(post("/rooms/" + roomId + "/files")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("path", "App.java"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.path").value("App.java"))
                .andExpect(jsonPath("$.language").value("java"))
                .andReturn().getResponse().getContentAsString();
        String fileId = objectMapper.readTree(createResp).get("id").asText();

        // Rename App.java -> Main.java; language stays java.
        mockMvc.perform(patch("/rooms/" + roomId + "/files/" + fileId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("path", "Main.java"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("Main.java"))
                .andExpect(jsonPath("$.language").value("java"));

        // Add a second file so we are allowed to delete the first.
        mockMvc.perform(post("/rooms/" + roomId + "/files")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("path", "script.py"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.language").value("python"));

        mockMvc.perform(delete("/rooms/" + roomId + "/files/" + fileId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/rooms/" + roomId + "/files")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].path").value("script.py"));
    }

    @Test
    void duplicatePathIsRejected() throws Exception {
        String token = register("files.dup@example.com");
        String roomId = createRoom(token, "Dup Files").get("id").asText();

        mockMvc.perform(post("/rooms/" + roomId + "/files")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("path", "App.java"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/rooms/" + roomId + "/files")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("path", "App.java"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cannotDeleteLastRemainingFile() throws Exception {
        String token = register("files.lastdel@example.com");
        String roomId = createRoom(token, "Last File").get("id").asText();

        // Auto-creates the default file.
        mockMvc.perform(get("/rooms/" + roomId + "/snapshot")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        String listResp = mockMvc.perform(get("/rooms/" + roomId + "/files")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        String onlyFileId = objectMapper.readTree(listResp).get(0).get("id").asText();

        mockMvc.perform(delete("/rooms/" + roomId + "/files/" + onlyFileId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonMemberCannotListOrCreateFiles() throws Exception {
        String ownerToken = register("files.owner.priv@example.com");
        String outsiderToken = register("files.outsider@example.com");
        String roomId = createRoom(ownerToken, "Private Files Room").get("id").asText();

        mockMvc.perform(get("/rooms/" + roomId + "/files")
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/rooms/" + roomId + "/files")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("path", "evil.txt"))))
                .andExpect(status().isNotFound());
    }

    private String register(String email) throws Exception {
        String response = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", "File Tester",
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
