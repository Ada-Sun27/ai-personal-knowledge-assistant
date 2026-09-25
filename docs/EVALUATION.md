# Evaluation methodology

## Inputs

A case contains `question`, a nonempty set of `relevantDocumentIds`, and `referenceAnswer`. IDs must exist among the ready documents in the current mode. The request specifies a list of chunking configurations, top-k values (1–10), and prompt variants (`concise` or `detailed`).

Use representative questions, including multi-source questions and queries that retrieve distracting passages. The bundled examples are a smoke dataset, not a quality benchmark for a broad domain. Unsupported-question behavior is covered by the empty-corpus integration test; the current labeled evaluation API requires at least one relevant document per case.

## What is compared

- Fixed-window versus paragraph-aware chunking.
- Window size and overlap, if supplied as additional configurations.
- Retrieval top-k.
- Concise versus detailed generation instructions.

For a controlled strategy comparison, hold size and overlap constant. `scripts/evaluate.py` does this. The UI preset instead demonstrates two combined configurations (fixed/400/60 and paragraph/800/120), so its differences cannot be attributed to strategy alone.

Each configuration gets a private `eval-*` collection. The production/main index is not mutated. Chunk embeddings are reused across top-k and prompt runs for the same chunking configuration. Retrieval is reused across prompt variants. Model generation is executed separately for each prompt variant.

## Metrics

| Metric | Definition | Caveat |
| --- | --- | --- |
| Document recall | Number of distinct relevant documents retrieved / number of labeled relevant documents | Labels are at document granularity, not exact passage granularity |
| Document precision | Number of distinct relevant documents retrieved / number of distinct documents retrieved | Duplicate chunks of a document are collapsed for this metric |
| Reciprocal rank | 1 / rank of the first chunk from a relevant document; 0 if none | Rank is chunk-level |
| Token F1 | Multiset token-overlap F1 between answer and reference, excluding `[S#]` and `[S#, S#]` markers | Does not measure semantic equivalence or factual entailment |
| Citation validity | Fraction of cases with no unknown source labels and no missing citations when sources were retrieved | Membership check only; abstention with no sources counts as valid |
| Timing breakdown | Query embedding, vector HTTP round trip, service processing, FAISS search | Embedding/model time is not part of the sub-100 ms vector benchmark |

Aggregate scores are simple means across cases. Per-case outputs include the answer, reference, sources, citation diagnostics and timings so you can inspect failures directly. There is no opaque LLM judge and no parsing failure silently converted to a zero score.

## Feedback workflow

A thumbs-down entry identifies a particular stored answer and retains its question, source snapshots and user comment. Use the review view to identify repeated failure patterns, then add labeled evaluation cases. Feedback does not automatically fine-tune the model or change ranking.

## Running an experiment

```bash
python scripts/evaluate.py --base-url http://127.0.0.1:8080 --output reports/evaluation.json
```

For custom experiments, use the JSON request in `API.md` or the UI. Bedrock evaluation may generate many paid requests; begin with a few labeled cases. Save the report's mode, corpus IDs and request parameters with your interpretation. Reports containing private excerpts should not be committed.
