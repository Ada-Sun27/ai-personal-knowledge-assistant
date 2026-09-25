package com.example.knowledge;

import static com.example.knowledge.Models.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.*;

@Component
public class VectorClient {
    private final RestClient client;
    public final String mainCollection;

    public VectorClient(
            RestClient.Builder builder,
            @Value("${app.vector-url}") String url,
            @Value("${app.mode}") String mode) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(60));
        client = builder.baseUrl(url).requestFactory(factory).build();
        mainCollection = mode + "-main";
    }

    public void replaceDocument(String collection, String documentId, List<VectorChunk> chunks) {
        client.put()
                .uri("/collections/{c}/documents/{d}", collection, documentId)
                .body(Map.of("chunks", chunks))
                .retrieve()
                .toBodilessEntity();
    }

    public void deleteDocument(String collection, String documentId) {
        client.delete()
                .uri("/collections/{c}/documents/{d}", collection, documentId)
                .retrieve()
                .toBodilessEntity();
    }

    public VectorResult search(String collection, float[] vector, int topK) {
        return Objects.requireNonNull(
                client.post()
                        .uri("/collections/{c}/search", collection)
                        .body(Map.of("vector", vector, "topK", topK, "minScore", 0.05))
                        .retrieve()
                        .body(VectorResult.class));
    }

    public boolean healthy() {
        try {
            client.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void deleteCollection(String collection) {
        client.delete().uri("/collections/{c}", collection).retrieve().toBodilessEntity();
    }
}
