package com.codeleon.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CodeRunnerService codeRunnerService;

    @MockBean
    private NixProjectRunnerService nixProjectRunnerService;

    @Test
    void runReturnsRunnerOutputForRoomMember() throws Exception {
        String token = register("runner.owner@example.com");
        JsonNode room = createRoom(token, "Runner Room");
        String roomId = room.get("id").asText();

        when(codeRunnerService.run(any())).thenReturn(new RunResult("hello\n", "", 0, 42L, false));

        mockMvc.perform(post("/rooms/" + roomId + "/run")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "language", "PYTHON",
                                "code", "print('hello')"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stdout").value("hello\n"))
                .andExpect(jsonPath("$.exitCode").value(0))
                .andExpect(jsonPath("$.timedOut").value(false));
    }

    @Test
    void runAcceptsJavaLanguageAndFilenameForRoomMember() throws Exception {
        String token = register("runner.java.owner@example.com");
        JsonNode room = createRoom(token, "Java Runner Room");
        String roomId = room.get("id").asText();

        when(codeRunnerService.run(any())).thenReturn(new RunResult("hello java\n", "", 0, 84L, false));

        mockMvc.perform(post("/rooms/" + roomId + "/run")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "language", "JAVA",
                                "filename", "src/Main.java",
                                "code", "public class Main { public static void main(String[] args) { System.out.println(\"hello java\"); } }",
                                "files", List.of(
                                        Map.of("path", "pom.xml", "text", "<project></project>"),
                                        Map.of("path", "src/Main.java", "text", "stale")
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stdout").value("hello java\n"))
                .andExpect(jsonPath("$.exitCode").value(0));

        ArgumentCaptor<RunRequest> requestCaptor = ArgumentCaptor.forClass(RunRequest.class);
        verify(codeRunnerService).run(requestCaptor.capture());
        assertEquals(RunLanguage.JAVA, requestCaptor.getValue().language());
        assertEquals("src/Main.java", requestCaptor.getValue().filename());
        assertEquals(2, requestCaptor.getValue().files().size());
    }

    @Test
    void runRejectsNonMember() throws Exception {
        String ownerToken = register("runner.owner.private@example.com");
        String outsiderToken = register("runner.outsider@example.com");
        JsonNode room = createRoom(ownerToken, "Private Runner Room");
        String roomId = room.get("id").asText();

        mockMvc.perform(post("/rooms/" + roomId + "/run")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "language", "PYTHON",
                                "code", "print('hi')"
                        ))))
                .andExpect(status().isNotFound());

        verify(codeRunnerService, never()).run(any());
    }

    @Test
    void runProjectReturnsNixRunnerOutputForRoomMember() throws Exception {
        String token = register("runner.project.owner@example.com");
        JsonNode room = createRoom(token, "Project Runner Room");
        String roomId = room.get("id").asText();

        when(nixProjectRunnerService.run(any())).thenReturn(new ProjectRunResult(
                "project ok\n",
                "",
                0,
                123L,
                false,
                "Generated Java/Maven",
                "mvn test",
                true,
                2,
                30000,
                "nixos/nix:2.24.11",
                List.of("codeleon-nix-store-test", "codeleon-maven-cache-test"),
                List.of()
        ));

        mockMvc.perform(post("/rooms/" + roomId + "/run/project")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "command", "mvn test",
                                "files", List.of(
                                        Map.of("path", "pom.xml", "text", "<project></project>"),
                                        Map.of("path", "src/main/java/App.java", "text", "class App {}")
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stdout").value("project ok\n"))
                .andExpect(jsonPath("$.exitCode").value(0))
                .andExpect(jsonPath("$.environment").value("Generated Java/Maven"))
                .andExpect(jsonPath("$.command").value("mvn test"))
                .andExpect(jsonPath("$.generatedEnvironment").value(true))
                .andExpect(jsonPath("$.runnerImage").value("nixos/nix:2.24.11"))
                .andExpect(jsonPath("$.cacheVolumes[0]").value("codeleon-nix-store-test"))
                .andExpect(jsonPath("$.cacheVolumes[1]").value("codeleon-maven-cache-test"));

        ArgumentCaptor<ProjectRunRequest> requestCaptor = ArgumentCaptor.forClass(ProjectRunRequest.class);
        verify(nixProjectRunnerService).run(requestCaptor.capture());
        assertEquals("mvn test", requestCaptor.getValue().command());
        assertEquals(2, requestCaptor.getValue().files().size());
    }

    @Test
    void runProjectRejectsNonMember() throws Exception {
        String ownerToken = register("runner.project.owner.private@example.com");
        String outsiderToken = register("runner.project.outsider@example.com");
        JsonNode room = createRoom(ownerToken, "Private Project Runner Room");
        String roomId = room.get("id").asText();

        mockMvc.perform(post("/rooms/" + roomId + "/run/project")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "files", List.of(Map.of("path", "package.json", "text", "{}"))
                        ))))
                .andExpect(status().isNotFound());

        verify(nixProjectRunnerService, never()).run(any());
    }

    @Test
    void detectProjectReturnsResolvedNixEnvironmentForRoomMember() throws Exception {
        String token = register("runner.project.detect.owner@example.com");
        JsonNode room = createRoom(token, "Project Detect Room");
        String roomId = room.get("id").asText();

        when(nixProjectRunnerService.detectRunnable(any())).thenReturn(new ProjectRunDetection(
                true,
                "Generated Node",
                "npm install && npm test",
                true,
                List.of(),
                null
        ));

        mockMvc.perform(post("/rooms/" + roomId + "/run/project/detect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "files", List.of(Map.of("path", "package.json", "text", "{\"scripts\":{\"test\":\"vitest\"}}"))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runnable").value(true))
                .andExpect(jsonPath("$.environment").value("Generated Node"))
                .andExpect(jsonPath("$.command").value("npm install && npm test"))
                .andExpect(jsonPath("$.generatedEnvironment").value(true));
    }

    private String register(String email) throws Exception {
        String response = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", "Runner Tester",
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
