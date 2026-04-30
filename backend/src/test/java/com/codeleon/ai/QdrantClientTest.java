package com.codeleon.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class QdrantClientTest {

    @Test
    void ensureCollectionCreatesWhenMissing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://localhost:6333/collections/codeleon-room-files"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        server.expect(requestTo("http://localhost:6333/collections/codeleon-room-files"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.vectors.size").value(768))
                .andExpect(jsonPath("$.vectors.distance").value("Cosine"))
                .andRespond(withSuccess("{\"result\":true,\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        QdrantClient client = new QdrantClient(builder, defaults());
        client.ensureCollection();

        server.verify();
    }

    @Test
    void ensureCollectionSkipsCreateWhenExists() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://localhost:6333/collections/codeleon-room-files"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"result\":{\"status\":\"green\"},\"status\":\"ok\"}",
                        MediaType.APPLICATION_JSON));

        QdrantClient client = new QdrantClient(builder, defaults());
        client.ensureCollection();

        server.verify();
    }

    @Test
    void searchReturnsScoredPoints() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://localhost:6333/collections/codeleon-room-files/points/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.limit").value(3))
                .andExpect(jsonPath("$.with_payload").value(true))
                .andRespond(withSuccess(
                        "{\"result\":[{\"id\":\"7c9c54d8-1e7a-4f46-9f8c-3c0b25f6a111\",\"score\":0.91,\"payload\":{\"roomId\":\"abc\"}}],\"status\":\"ok\"}",
                        MediaType.APPLICATION_JSON));

        QdrantClient client = new QdrantClient(builder, defaults());
        List<QdrantClient.ScoredPoint> hits = client.search(new float[]{0.1f, 0.2f, 0.3f}, 3, null);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).id()).isEqualTo(UUID.fromString("7c9c54d8-1e7a-4f46-9f8c-3c0b25f6a111"));
        assertThat(hits.get(0).score()).isEqualTo(0.91d);
        assertThat(hits.get(0).payload()).containsEntry("roomId", "abc");
        server.verify();
    }

    @Test
    void upsertSendsPointsPayload() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://localhost:6333/collections/codeleon-room-files/points?wait=true"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.points[0].id").exists())
                .andExpect(jsonPath("$.points[0].payload.fileId").value("file-1"))
                .andRespond(withSuccess("{\"result\":{\"status\":\"completed\"},\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        QdrantClient client = new QdrantClient(builder, defaults());
        client.upsert(List.of(new QdrantClient.Point(
                UUID.randomUUID(),
                new float[]{0.1f, 0.2f, 0.3f},
                Map.of("fileId", "file-1")
        )));

        server.verify();
    }

    private static AiProperties defaults() {
        return new AiProperties(false, null, null);
    }
}
