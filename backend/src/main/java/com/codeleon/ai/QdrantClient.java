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

    /** Cap on points per upsert request. A single PUT carrying ~50 chunks of
     *  768-dim vectors plus text payloads is large enough to occasionally
     *  trip "Error writing request body" between the backend and Qdrant on
     *  this single-host deployment. Smaller batches eliminate that. */
    private static final int UPSERT_BATCH_SIZE = 32;
    /** Retries per batch on a transient I/O / 5xx error before giving up. */
    private static final int UPSERT_MAX_RETRIES = 2;

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
        if (points == null || points.isEmpty()) return;
        // Split into bounded sub-batches: a big single PUT was the source of
        // the "Error writing request body to server" failures seen on prod
        // (Qdrant itself was happy — the error came from the request-write
        // side). Retry each sub-batch a couple of times to absorb transient
        // network blips; a sub-batch that still fails is rethrown and only
        // its slice of chunks is lost, not the whole file.
        for (int i = 0; i < points.size(); i += UPSERT_BATCH_SIZE) {
            List<Point> batch = points.subList(i, Math.min(i + UPSERT_BATCH_SIZE, points.size()));
            upsertBatchWithRetry(batch, i);
        }
    }

    private void upsertBatchWithRetry(List<Point> batch, int offset) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= UPSERT_MAX_RETRIES; attempt++) {
            try {
                http.put()
                        .uri("/collections/{name}/points?wait=true", config.collection())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("points", batch))
                        .retrieve()
                        .toBodilessEntity();
                return;
            } catch (RuntimeException ex) {
                last = ex;
                if (attempt == UPSERT_MAX_RETRIES) break;
                log.debug("Qdrant upsert batch (offset {}, size {}) failed (attempt {}): {} — retrying",
                        offset, batch.size(), attempt + 1, ex.getMessage());
                try {
                    Thread.sleep(150L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }

    public void deleteByFilter(Map<String, Object> filter) {
        http.post()
                .uri("/collections/{name}/points/delete?wait=true", config.collection())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("filter", filter))
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

    /**
     * Returns the number of indexed points in the collection. Used by the
     * admin stats endpoint. Throws if Qdrant is unreachable so the caller
     * can decide whether to fall back to a placeholder value.
     */
    public long countPoints() {
        CollectionInfoResponse response = http.get()
                .uri("/collections/{name}", config.collection())
                .retrieve()
                .body(CollectionInfoResponse.class);
        if (response == null || response.result() == null) return 0L;
        return response.result().pointsCount();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CollectionInfoResponse(CollectionInfo result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CollectionInfo(
            @com.fasterxml.jackson.annotation.JsonProperty("points_count") long pointsCount
    ) {
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
