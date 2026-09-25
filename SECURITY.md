# Security and privacy scope

This application is a single-user local learning project. The default Compose ports bind to loopback, and the vector service has no host port. The application has no login or tenant isolation; do not publish its ports as a shared service without adding authentication and authorization.

Demo mode makes no AI-provider calls. Bedrock mode sends document chunks for embeddings and sends retrieved excerpts plus the question for answer generation. Local document text, vector snapshots, saved answers, feedback, and evaluation output are stored without application-level encryption.

Deleting a document removes it from active retrieval. Historical answers and evaluation reports retain source snapshots by design so feedback remains auditable. Delete the corresponding local data when you need complete erasure.

The service reads only locally generated FAISS snapshots. Never replace snapshots with untrusted binary files. Text is rendered with React escaping; model and document HTML is not executed. Citation validation checks source labels, not factual entailment. The grounding instruction reduces prompt-injection risk but does not guarantee resistance.

Report vulnerabilities through the repository's private security advisory feature, if enabled. Do not include secrets or private documents in public issues.
