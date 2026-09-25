package com.example.knowledge;

import static com.example.knowledge.Models.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "${app.web-origin}")
public class KnowledgeController {
    private final DocumentService documents;
    private final AnswerService answers;
    private final EvaluationService evaluation;
    private final VectorClient vectors;
    private final ThreadPoolTaskExecutor executor;
    private final ObjectMapper mapper;
    private final String mode;

    public KnowledgeController(
            DocumentService documents,
            AnswerService answers,
            EvaluationService evaluation,
            VectorClient vectors,
            ThreadPoolTaskExecutor executor,
            ObjectMapper mapper,
            @Value("${app.mode}") String mode) {
        this.documents = documents;
        this.answers = answers;
        this.evaluation = evaluation;
        this.vectors = vectors;
        this.executor = executor;
        this.mapper = mapper;
        this.mode = mode;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean ready = vectors.healthy();
        return ResponseEntity.status(ready ? 200 : 503)
                .body(Map.of("status", ready ? "UP" : "DEGRADED", "mode", mode));
    }

    @GetMapping("/documents")
    public List<DocumentSummary> documents() {
        return documents.list();
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentSummary upload(
            @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "paragraph") String strategy,
            @RequestParam(defaultValue = "800") int chunkSize,
            @RequestParam(defaultValue = "120") int overlap)
            throws Exception {
        return documents.ingest(file, new Chunking(strategy, chunkSize, overlap));
    }

    @PostMapping("/documents/{id}/retry")
    public DocumentSummary retry(@PathVariable String id) {
        return documents.retry(id);
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        documents.delete(id);
    }

    @PostMapping("/ask")
    public AnswerRecord ask(@Valid @RequestBody Ask request) throws Exception {
        return answers.answer(request, (event, payload) -> {});
    }

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@Valid @RequestBody Ask request) {
        SseEmitter emitter = new SseEmitter(180_000L);
        AtomicReference<Future<?>> running = new AtomicReference<>();
        Runnable cancel =
                () -> {
                    Future<?> task = running.get();
                    if (task != null && !task.isDone()) task.cancel(true);
                };
        emitter.onTimeout(cancel);
        emitter.onError(error -> cancel.run());
        running.set(
                executor.submit(
                        () -> {
                            try {
                                answers.answer(
                                        request,
                                        (name, payload) ->
                                                emitter.send(
                                                        SseEmitter.event()
                                                                .name(name)
                                                                .data(
                                                                        mapper.writeValueAsString(
                                                                                payload))));
                                emitter.complete();
                            } catch (Exception error) {
                                try {
                                    emitter.send(
                                            SseEmitter.event()
                                                    .name("error")
                                                    .data(
                                                            mapper.writeValueAsString(
                                                                    Map.of(
                                                                            "message",
                                                                            "Answer generation"
                                                                                + " failed. Check"
                                                                                + " the server log"
                                                                                + " and provider"
                                                                                + " configuration."))));
                                    emitter.complete();
                                } catch (Exception disconnected) {
                                    emitter.completeWithError(error);
                                }
                                org.slf4j.LoggerFactory.getLogger(getClass())
                                        .warn("Answer stream failed", error);
                            }
                        }));
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    @GetMapping("/answers")
    public List<AnswerRecord> answers(@RequestParam(defaultValue = "false") boolean negativeOnly) {
        return answers.list(negativeOnly);
    }

    @GetMapping("/answers/{id}")
    public AnswerRecord answer(@PathVariable String id) {
        return answers.get(id);
    }

    @PostMapping("/answers/{id}/feedback")
    public AnswerRecord feedback(@PathVariable String id, @Valid @RequestBody Rating rating) {
        return answers.feedback(id, rating);
    }

    @PostMapping("/evaluations")
    public Map<String, Object> evaluate(@Valid @RequestBody EvalRequest request) {
        return evaluation.run(request);
    }

    @GetMapping("/evaluations/{id}")
    public Map<?, ?> evaluation(@PathVariable String id) {
        return evaluation.get(id);
    }
}
