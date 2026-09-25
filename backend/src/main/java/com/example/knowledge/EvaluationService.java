package com.example.knowledge;

import static com.example.knowledge.Models.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class EvaluationService {
    private final DocumentService documents;
    private final VectorClient vectors;
    private final RetrievalService retrieval;
    private final AiGateway ai;
    private final JsonStore store;
    private final String mode;

    public EvaluationService(
            DocumentService documents,
            VectorClient vectors,
            RetrievalService retrieval,
            AiGateway ai,
            JsonStore store,
            @Value("${app.mode}") String mode) {
        this.documents = documents;
        this.vectors = vectors;
        this.retrieval = retrieval;
        this.ai = ai;
        this.store = store;
        this.mode = mode;
    }

    /**
     * Synchronous offline workflow. Reuse each chunking index across top-k and prompt combinations.
     */
    public Map<String, Object> run(EvalRequest request) {
        List<DocumentRecord> corpus = documents.ready();
        Set<String> ids = new HashSet<>();
        corpus.forEach(d -> ids.add(d.id()));
        if (corpus.isEmpty())
            throw new IllegalArgumentException("Upload documents before evaluating");
        for (EvalCase c : request.cases()) {
            if (!ids.containsAll(c.relevantDocumentIds()))
                throw new IllegalArgumentException("Evaluation references unknown document IDs");
        }
        String runId = UUID.randomUUID().toString();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Chunking spec : request.chunkings()) {
            String collection = "eval-" + runId + "-" + UUID.randomUUID();
            try {
                for (DocumentRecord d : corpus) documents.index(collection, d, spec);
                for (int k : request.topKs()) {
                    // The same retrieval is shared across prompt variants to make the comparison
                    // fair.
                    List<Retrieval> found =
                            request.cases().stream()
                                    .map(c -> retrieval.retrieve(collection, c.question(), k))
                                    .toList();
                    for (String prompt : request.promptVariants()) {
                        List<Map<String, Object>> cases = new ArrayList<>();
                        for (int i = 0; i < request.cases().size(); i++) {
                            EvalCase c = request.cases().get(i);
                            Retrieval r = found.get(i);
                            String answer =
                                    r.sources().isEmpty()
                                            ? "Insufficient evidence."
                                            : ai.generate(c.question(), r.sources(), prompt)
                                                    .collectList()
                                                    .map(tokens -> String.join("", tokens))
                                                    .block();
                            Set<String> relevant = new HashSet<>(c.relevantDocumentIds());
                            Set<String> retrieved = new LinkedHashSet<>();
                            r.sources().forEach(s -> retrieved.add(s.documentId()));
                            long hits = retrieved.stream().filter(relevant::contains).count();
                            double reciprocalRank = 0;
                            for (int rank = 0; rank < r.sources().size(); rank++) {
                                if (relevant.contains(r.sources().get(rank).documentId())) {
                                    reciprocalRank = 1.0 / (rank + 1);
                                    break;
                                }
                            }
                            CitationCheck citation = QualityMetrics.citations(answer, r.sources());
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("question", c.question());
                            row.put("answer", answer);
                            row.put("referenceAnswer", c.referenceAnswer());
                            row.put("sources", r.sources());
                            row.put("documentRecall", (double) hits / relevant.size());
                            row.put(
                                    "documentPrecision",
                                    retrieved.isEmpty() ? 0.0 : (double) hits / retrieved.size());
                            row.put("reciprocalRank", reciprocalRank);
                            row.put("tokenF1", QualityMetrics.tokenF1(answer, c.referenceAnswer()));
                            row.put(
                                    "citationsValid",
                                    citation.unknownLabels().isEmpty()
                                            && !citation.missingCitations());
                            row.put("citations", citation);
                            row.put("timings", r);
                            cases.add(row);
                        }
                        Map<String, Object> combination = new LinkedHashMap<>();
                        combination.put("chunking", spec);
                        combination.put("topK", k);
                        combination.put("promptVariant", prompt);
                        combination.put("cases", cases);
                        for (String metric :
                                List.of(
                                        "documentRecall",
                                        "documentPrecision",
                                        "reciprocalRank",
                                        "tokenF1")) {
                            combination.put(
                                    metric,
                                    cases.stream()
                                            .mapToDouble(
                                                    row -> ((Number) row.get(metric)).doubleValue())
                                            .average()
                                            .orElse(0));
                        }
                        combination.put(
                                "citationValidity",
                                cases.stream()
                                                .filter(
                                                        row ->
                                                                Boolean.TRUE.equals(
                                                                        row.get("citationsValid")))
                                                .count()
                                        / (double) cases.size());
                        rows.add(combination);
                    }
                }
            } finally {
                vectors.deleteCollection(collection);
            }
        }
        Map<String, Object> result =
                Map.of(
                        "id",
                        runId,
                        "mode",
                        mode,
                        "createdAt",
                        Instant.now().toString(),
                        "corpusIds",
                        ids,
                        "request",
                        request,
                        "results",
                        rows,
                        "metricNote",
                        "Token F1 and citation validity are proxy metrics, not factual entailment"
                            + " judgments.");
        store.put("evaluations", runId, result);
        return result;
    }

    public Map<?, ?> get(String id) {
        return store.get("evaluations", id, Map.class)
                .orElseThrow(() -> new NoSuchElementException("Evaluation not found"));
    }
}
