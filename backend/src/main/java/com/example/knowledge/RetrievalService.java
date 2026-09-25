package com.example.knowledge;

import static com.example.knowledge.Models.*;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RetrievalService {
    private final AiGateway ai;
    private final VectorClient vectors;

    public RetrievalService(AiGateway ai, VectorClient vectors) {
        this.ai = ai;
        this.vectors = vectors;
    }

    public Retrieval retrieve(String collection, String question, int topK) {
        long start = System.nanoTime();
        float[] query = ai.embed(question);
        double embeddingMs = elapsed(start);
        long vectorStart = System.nanoTime();
        VectorResult result = vectors.search(collection, query, topK);
        double vectorHttpMs = elapsed(vectorStart);
        List<Source> sources = new ArrayList<>();
        for (Hit hit : result.results()) {
            sources.add(
                    new Source(
                            "S" + (sources.size() + 1),
                            hit.id(),
                            hit.documentId(),
                            hit.filename(),
                            hit.chunkIndex(),
                            hit.start(),
                            hit.end(),
                            hit.text(),
                            hit.score()));
        }
        return new Retrieval(
                sources, embeddingMs, vectorHttpMs, result.searchMs(), result.serviceMs());
    }

    private double elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000.0;
    }
}
