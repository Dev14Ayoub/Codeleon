package com.codeleon.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OllamaClientTest {

    @Test
    void embedReturnsVectorFromOllama() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://localhost:11434/api/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model").value("nomic-embed-text"))
                .andExpect(jsonPath("$.prompt").value("hello world"))
                .andRespond(withSuccess(
                        "{\"embedding\":[0.1,0.2,0.3]}",
                        MediaType.APPLICATION_JSON));

        OllamaClient client = new OllamaClient(builder, defaults());
        float[] vector = client.embed("hello world");

        assertThat(vector).hasSize(3);
        assertThat(vector[0]).isCloseTo(0.1f, within(1e-5f));
        assertThat(vector[2]).isCloseTo(0.3f, within(1e-5f));
        server.verify();
    }

    @Test
    void chatReturnsAssistantContent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://localhost:11434/api/chat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model").value("qwen2.5-coder:0.5b"))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andRespond(withSuccess(
                        "{\"message\":{\"role\":\"assistant\",\"content\":\"hi there\"},\"done\":true}",
                        MediaType.APPLICATION_JSON));

        OllamaClient client = new OllamaClient(builder, defaults());
        String reply = client.chat(List.of(OllamaClient.ChatMessage.user("ping")));

        assertThat(reply).isEqualTo("hi there");
        server.verify();
    }

    private static AiProperties defaults() {
        return new AiProperties(false, null, null);
    }
}
