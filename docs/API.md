# API and configuration

Base URL: `http://localhost:8080/api`. The API is single-user and intended for localhost. JSON errors use an `error` field; SSE failures use an `error` event.

## Endpoints

| Method and route | Request | Result |
| --- | --- | --- |
| `GET /health` | None | API mode and vector dependency health (200/503); does not call Bedrock |
| `GET /documents` | None | Document summaries and ingestion states |
| `POST /documents` | Multipart `file`, `strategy`, `chunkSize`, `overlap` | Saved document summary |
| `POST /documents/{id}/retry` | None | Re-index document idempotently |
| `DELETE /documents/{id}` | None | 204; remove active document and vectors |
| `POST /ask` | Question, top-k and prompt variant | Complete persisted answer |
| `POST /ask/stream` | Same as `/ask` | Named SSE frames |
| `GET /answers` | Optional `negativeOnly=true` | Stored answers and feedback, newest first |
| `GET /answers/{id}` | None | One answer with source snapshots |
| `POST /answers/{id}/feedback` | `rating` (+1 or -1), optional `comment` | Updated answer record |
| `POST /evaluations` | Cases and experiment matrix | Persisted evaluation report |
| `GET /evaluations/{id}` | None | Prior report |

## Upload

```bash
curl -F 'file=@examples/refund-policy.md' \
  -F 'strategy=paragraph' -F 'chunkSize=800' -F 'overlap=120' \
  http://localhost:8080/api/documents
```

A normal document progresses through `INDEXING → READY`. Failed indexing remains retryable. Deletion uses `DELETING` while the vector update is in progress.

## Ask and stream

```bash
curl -N -X POST http://localhost:8080/api/ask/stream \
  -H 'Content-Type: application/json' \
  -d '{"question":"What is the refund deadline?","topK":4,"promptVariant":"concise"}'
```

The event order is `sources → token* → done`. On failure, an `error` event replaces normal completion. Each event's data is JSON. Example framing:

```text
event:sources
data:{"id":"...","sources":[...],"timings":{...},"mode":"demo"}

event:token
data:{"text":"Refunds are available "}

event:done
data:{"id":"...","question":"...","answer":"...","status":"COMPLETE",...}

```

The `done` event contains the final persisted answer. `sources` exposes metadata early. A client must not treat a connection closing without `done` as success.

## Feedback

```bash
curl -X POST http://localhost:8080/api/answers/ANSWER_ID/feedback \
  -H 'Content-Type: application/json' -d '{"rating":-1,"comment":"The answer missed the receipt requirement."}'
```

Only a stored complete answer accepts feedback. Rating 0 is rejected. Updating feedback replaces the prior rating/comment for that answer.

## Evaluation

```json
{
  "cases": [{
    "question": "What is the refund deadline?",
    "relevantDocumentIds": ["REPLACE_WITH_DOCUMENT_ID"],
    "referenceAnswer": "Refunds are available within 30 days of purchase."
  }],
  "chunkings": [
    {"strategy": "fixed", "size": 200, "overlap": 30},
    {"strategy": "paragraph", "size": 200, "overlap": 30}
  ],
  "topKs": [1, 4],
  "promptVariants": ["concise", "detailed"]
}
```

Save this as a local file and submit using `curl --max-time 900 -H 'Content-Type: application/json' --data-binary @request.json http://localhost:8080/api/evaluations`. Up to 20 cases, 4 chunking configurations, 3 top-k settings, and 2 prompt variants are allowed. This is a synchronous offline workflow.

## Environment

| Variable | Default | Meaning |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `demo` via default profile | Native backend provider mode; use `demo` or `bedrock` |
| `APP_PROFILE` | `demo` | Compose mode selector, passed to Spring |
| `APP_DATA_DIR` | `./data` | Backend record root; records are separated by mode |
| `VECTOR_DATA_DIR` | `./data` | FAISS service snapshot root |
| `VECTOR_URL` | `http://127.0.0.1:8000` | Backend-to-FAISS endpoint |
| `SERVER_PORT` | `8080` | Java HTTP port |
| `SERVER_ADDRESS` | `127.0.0.1` | Native backend bind address; Compose overrides internally |
| `APP_WEB_ORIGIN` | `http://localhost:5173` | Allowed browser origin for direct API requests |
| `VITE_API_URL` | Empty (same-origin `/api`) | Optional browser API origin; set before building/starting Vite |
| `AWS_REGION` | `us-east-1` | Bedrock region |
| `BEDROCK_CHAT_MODEL` | `amazon.nova-lite-v1:0` | Converse model or inference profile ID |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` | AWS credential chain | Credentials for native or Compose usage |

Native Vite proxies `/api`; Nginx does the same in Compose with buffering disabled for SSE. Configuring a different browser/API origin requires a matching `APP_WEB_ORIGIN`.

## Common failures

- **503 health:** start the vector service and check `VECTOR_URL`.
- **502 on Bedrock call:** inspect the backend log for region, IAM, model availability or quota errors.
- **400 upload:** unsupported file, invalid UTF-8, too many chunks, unreadable/encrypted PDF or no extractable text.
- **413 upload:** the file exceeds 10 MB.
- **Empty retrieval:** upload documents in the current mode; demo and Bedrock collections are intentionally separate.
- **Invalid source warning:** inspect the excerpt and answer; membership checks do not guarantee correctness.
- **Stale `INDEXING`/`DELETING`:** restore provider/index availability and retry the respective operation.
