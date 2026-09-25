package com.example.knowledge;

import static com.example.knowledge.Models.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class AnswerService {
    @FunctionalInterface
    public interface EventSink {
        void send(String event, Object payload) throws Exception;
    }

    private final AiGateway ai;
    private final RetrievalService retrieval;
    private final VectorClient vectors;
    private final JsonStore store;
    private final String mode;

    public AnswerService(
            AiGateway ai,
            RetrievalService retrieval,
            VectorClient vectors,
            JsonStore store,
            @Value("${app.mode}") String mode) {
        this.ai = ai;
        this.retrieval = retrieval;
        this.vectors = vectors;
        this.store = store;
        this.mode = mode;
    }

    public AnswerRecord answer(Ask request, EventSink sink) throws Exception {
        String id = UUID.randomUUID().toString();
        String createdAt = Instant.now().toString();
        Retrieval found =
                retrieval.retrieve(vectors.mainCollection, request.question(), request.topK());
        StringBuilder text = new StringBuilder();
        try {
            sink.send(
                    "sources",
                    Map.of("id", id, "sources", found.sources(), "timings", found, "mode", mode));
            if (found.sources().isEmpty()) {
                text.append("I couldn't find supporting information in the indexed documents.");
                sink.send("token", Map.of("text", text.toString()));
            } else {
                // Closing the stream cancels the provider subscription when the client disconnects.
                try (var stream =
                        ai.generate(request.question(), found.sources(), request.promptVariant())
                                .toStream()) {
                    var iterator = stream.iterator();
                    while (iterator.hasNext()) {
                        String token = iterator.next();
                        text.append(token);
                        sink.send("token", Map.of("text", token));
                    }
                }
            }
            var complete = record(id, request, text.toString(), found, "COMPLETE", createdAt);
            store.put("answers", id, complete);
            sink.send("done", complete);
            return complete;
        } catch (Exception error) {
            // Persist partial output for diagnostics. It is not eligible for normal feedback.
            store.put(
                    "answers",
                    id,
                    record(id, request, text.toString(), found, "INCOMPLETE", createdAt));
            throw error;
        }
    }

    private AnswerRecord record(
            String id, Ask request, String text, Retrieval found, String status, String at) {
        return new AnswerRecord(
                id,
                request.question(),
                text,
                found.sources(),
                found,
                QualityMetrics.citations(text, found.sources()),
                status,
                mode,
                request.promptVariant(),
                at,
                null);
    }

    public AnswerRecord get(String id) {
        return store.get("answers", id, AnswerRecord.class)
                .orElseThrow(() -> new NoSuchElementException("Answer not found"));
    }

    public List<AnswerRecord> list(boolean negativeOnly) {
        return store.list("answers", AnswerRecord.class).stream()
                .filter(a -> !negativeOnly || (a.feedback() != null && a.feedback().rating() == -1))
                .sorted(Comparator.comparing(AnswerRecord::createdAt).reversed())
                .toList();
    }

    public synchronized AnswerRecord feedback(String id, Rating rating) {
        if (rating.rating() != 1 && rating.rating() != -1)
            throw new IllegalArgumentException("rating must be 1 or -1");
        var old = get(id);
        if (!"COMPLETE".equals(old.status()))
            throw new IllegalArgumentException("Only complete answers accept feedback");
        var updated =
                new AnswerRecord(
                        old.id(),
                        old.question(),
                        old.answer(),
                        old.sources(),
                        old.timings(),
                        old.citations(),
                        old.status(),
                        old.mode(),
                        old.promptVariant(),
                        old.createdAt(),
                        new Feedback(
                                rating.rating(),
                                Optional.ofNullable(rating.comment()).orElse(""),
                                Instant.now().toString()));
        store.put("answers", id, updated);
        return updated;
    }
}
