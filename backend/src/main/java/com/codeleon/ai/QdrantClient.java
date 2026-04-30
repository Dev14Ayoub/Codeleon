package com.codeleon.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class QdrantClient {

    private static final Logger log = LoggerFactory.getLogger(QdrantClient.class);

    private final RestClient http;
    private final AiProperties.Qdrant config;

    public QdrantClient(RestClient.Builder builder, AiProperties props) {
        this.config = props.qdrant();
        this.http = builder.baseUrl(config.url()).build();
    }

    public void ensureCollection() {
        boolean exists = http.get()
                .uri("/collections/{name}", config.collection())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> { /* swallow 404 */ })
                .toBodilessEntity()
                .getStatusCode()
                .is2xxSuccessful();

        if (exists) {
            log.debug("Qdrant collection '{}' already exists", config.collection());
            return;
        }

        Map<String, Object> body = Map.of(
                "vectors", Map.of(
                        "size", config.vectorSize(),
                        "distance", config.distance()
                )
        );

        http.put()
                .uri("/collections/{name}", config.collection())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();

        log.info("Created Qdrant collection '{}' (size={}, distance={})",
                config.collection(), config.vectorSize(), config.distance());
    }

    public void upsert(List<Point> points) {
        http.put()
                .uri("/collections/{name}/points?wait=true", config.collection())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("points", points))
                .retrieve()
                .toBodilessEntity();
    }

    public List<ScoredPoint> search(float[] vector, int topK, Map<String, Object> filter) {
        SearchRequest req = new SearchRequest(vector, topK, true, filter);
        SearchResponse response = http.post()
                .uri("/collections/{name}/points/search", config.collection())
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(SearchResponse.class);
        return response == null || response.result() == null ? List.of() : response.result();
    }

    public AiProperties.Qdrant config() {
        return config;
    }

    public record Point(UUID id, float[] vector, Map<String, Object> payload) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScoredPoint(UUID id, double score, Map<String, Object> payload) {
    }

    record SearchRequest(
            float[] vector,
            int limit,
            @com.fasterxml.jackson.annotation.JsonProperty("with_payload") boolean withPayload,
            Map<String, Object> filter
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<ScoredPoint> result) {
    }
}
