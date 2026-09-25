package com.example.knowledge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/** Shared API records. Domain objects remain immutable across asynchronous requests. */
public final class Models {
    private Models() {}

    public record Chunking(
            @Pattern(regexp = "fixed|paragraph") String strategy,
            @Min(100) @Max(4000) int size,
            @Min(0) int overlap) {
        public Chunking {
            if (!("fixed".equals(strategy) || "paragraph".equals(strategy))
                    || size < 100
                    || size > 4000
                    || overlap < 0
                    || overlap >= size) {
                throw new IllegalArgumentException(
                        "Use fixed/paragraph, size 100..4000, overlap 0..size-1");
            }
        }
    }

    public record Chunk(
            String id,
            String documentId,
            String filename,
            int chunkIndex,
            int start,
            int end,
            String text) {}

    public record VectorChunk(
            String id,
            String documentId,
            String filename,
            int chunkIndex,
            int start,
            int end,
            String text,
            float[] vector) {}

    public record Hit(
            String id,
            String documentId,
            String filename,
            int chunkIndex,
            int start,
            int end,
            String text,
            double score) {}

    public record Source(
            String label,
            String id,
            String documentId,
            String filename,
            int chunkIndex,
            int start,
            int end,
            String text,
            double score) {}

    public record VectorResult(List<Hit> results, double searchMs, double serviceMs) {}

    public record Retrieval(
            List<Source> sources,
            double embeddingMs,
            double vectorHttpMs,
            double searchMs,
            double serviceMs) {}

    public record DocumentRecord(
            String id,
            String filename,
            String text,
            Chunking chunking,
            int chunks,
            String state,
            String createdAt) {}

    public record DocumentSummary(
            String id,
            String filename,
            Chunking chunking,
            int chunks,
            String state,
            String createdAt) {}

    public record Ask(
            @NotBlank @Size(max = 4000) String question,
            @Min(1) @Max(10) int topK,
            @NotBlank @Pattern(regexp = "concise|detailed") String promptVariant) {}

    public record CitationCheck(
            List<String> citedLabels, List<String> unknownLabels, boolean missingCitations) {}

    public record Rating(@Min(-1) @Max(1) int rating, @Size(max = 2000) String comment) {}

    public record Feedback(int rating, String comment, String createdAt) {}

    public record AnswerRecord(
            String id,
            String question,
            String answer,
            List<Source> sources,
            Retrieval timings,
            CitationCheck citations,
            String status,
            String mode,
            String promptVariant,
            String createdAt,
            Feedback feedback) {}

    public record EvalCase(
            @NotBlank String question,
            @NotEmpty List<String> relevantDocumentIds,
            @NotBlank String referenceAnswer) {}

    public record EvalRequest(
            @NotEmpty @Size(max = 20) List<@NotNull @Valid EvalCase> cases,
            @NotEmpty @Size(max = 4) List<@NotNull @Valid Chunking> chunkings,
            @NotEmpty @Size(max = 3) List<@NotNull @Min(1) @Max(10) Integer> topKs,
            @NotEmpty @Size(max = 2)
                    List<@NotBlank @Pattern(regexp = "concise|detailed") String> promptVariants) {}
}
