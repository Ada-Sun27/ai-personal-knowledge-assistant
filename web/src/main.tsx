import { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { askStream, json, request } from "./api";
import { CitedAnswer } from "./CitedAnswer";
import type { Answer, Document, Evaluation, Source } from "./types";
import "./style.css";

function App() {
  const [mode, setMode] = useState("connecting");
  const [documents, setDocuments] = useState<Document[]>([]);
  const [file, setFile] = useState<File | null>(null);
  const [strategy, setStrategy] = useState("paragraph");
  const [question, setQuestion] = useState("");
  const [topK, setTopK] = useState(4);
  const [prompt, setPrompt] = useState("concise");
  const [answer, setAnswer] = useState<Answer | null>(null);
  const [text, setText] = useState("");
  const [sources, setSources] = useState<Source[]>([]);
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [history, setHistory] = useState<Answer[]>([]);
  const [negativeOnly, setNegativeOnly] = useState(true);
  const [evalCases, setEvalCases] = useState("");
  const [evaluation, setEvaluation] = useState<Evaluation | null>(null);

  async function refresh() {
    setDocuments(await request<Document[]>("/documents"));
  }
  useEffect(() => {
    request<{ mode: string }>("/health")
      .then((r) => setMode(r.mode))
      .catch((e) => {
        setMode("offline");
        setError(String(e));
      });
    refresh().catch((e) => setError(String(e)));
  }, []);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await action();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  function upload() {
    void run(async () => {
      if (!file) return;
      const form = new FormData();
      form.append("file", file);
      form.append("strategy", strategy);
      setMessage("Extracting text, embedding chunks and saving the index…");
      const result = await request<Document>("/documents", {
        method: "POST",
        body: form,
      });
      await refresh();
      setMessage(`Indexed ${result.filename}: ${result.chunks} chunks.`);
    });
  }

  function ask() {
    void run(async () => {
      setAnswer(null);
      setText("");
      setSources([]);
      setComment("");
      setMessage("Retrieving sources…");
      await askStream(question, topK, prompt, (event) => {
        if (event.name === "sources") {
          setSources((event.data as { sources: Source[] }).sources);
          setMessage("Generating answer…");
        }
        if (event.name === "token")
          setText(
            (previous) => previous + (event.data as { text: string }).text,
          );
        if (event.name === "done") {
          const complete = event.data as Answer;
          setAnswer(complete);
          setText(complete.answer);
          setMessage("Answer saved.");
        }
      });
    });
  }

  function feedback(rating: number) {
    void run(async () => {
      if (!answer) return;
      setAnswer(
        await request<Answer>(
          `/answers/${answer.id}/feedback`,
          json({ rating, comment }),
        ),
      );
      setMessage(
        "Feedback saved with the question, answer and source excerpts.",
      );
    });
  }

  function loadHistory() {
    void run(async () => {
      setHistory(
        await request<Answer[]>(`/answers?negativeOnly=${negativeOnly}`),
      );
      setMessage("Answer history refreshed.");
    });
  }

  function seedEvaluation() {
    const first = documents.find((d) => d.state === "READY");
    setEvalCases(
      JSON.stringify(
        [
          {
            question: "What is the refund deadline?",
            relevantDocumentIds: first ? [first.id] : [],
            referenceAnswer:
              "Refunds are available within 30 days of purchase.",
          },
        ],
        null,
        2,
      ),
    );
  }

  function evaluate() {
    void run(async () => {
      const cases: unknown = JSON.parse(evalCases);
      setMessage(
        "Evaluating two chunking strategies, two top-k values and two prompts. Bedrock mode makes paid calls.",
      );
      setEvaluation(
        await request<Evaluation>(
          "/evaluations",
          json({
            cases,
            chunkings: [
              { strategy: "fixed", size: 400, overlap: 60 },
              { strategy: "paragraph", size: 800, overlap: 120 },
            ],
            topKs: [2, 4],
            promptVariants: ["concise", "detailed"],
          }),
        ),
      );
      setMessage("Evaluation complete and saved.");
    });
  }

  function downloadEvaluation() {
    if (!evaluation) return;
    const url = URL.createObjectURL(
      new Blob([JSON.stringify(evaluation, null, 2)], {
        type: "application/json",
      }),
    );
    const link = document.createElement("a");
    link.href = url;
    link.download = `evaluation-${evaluation.id}.json`;
    link.click();
    URL.revokeObjectURL(url);
  }

  return (
    <main>
      <header>
        <div>
          <p className="eyebrow">YOUR DOCUMENTS · YOUR SOURCES</p>
          <h1>AI Personal Knowledge Assistant</h1>
          <p>Find answers, follow the evidence, and improve retrieval.</p>
        </div>
        <span className="mode">{mode.toUpperCase()}</span>
      </header>
      {mode === "demo" && (
        <p className="notice">
          Demo mode uses deterministic embeddings and quoted excerpts. Switch to
          Bedrock for LLM answers.
        </p>
      )}
      <div role="status" className="status">
        {message}
      </div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <div className="workspace">
        <aside>
          <section>
            <h2>Document collection</h2>
            <p className="muted">
              UTF-8 text, Markdown or text-based PDF · up to 10 MB
            </p>
            <input
              aria-label="Document file"
              type="file"
              accept=".txt,.md,.pdf"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
            <label>
              Chunking strategy
              <select
                value={strategy}
                onChange={(e) => setStrategy(e.target.value)}
              >
                <option value="paragraph">Paragraph aware</option>
                <option value="fixed">Fixed window</option>
              </select>
            </label>
            <button disabled={!file || busy} onClick={upload}>
              Upload and index
            </button>
            <ul className="documents">
              {documents.map((doc) => (
                <li key={doc.id}>
                  <strong>{doc.filename}</strong>
                  <small>
                    {doc.chunks} chunks · {doc.state}
                  </small>
                  <code title={doc.id}>{doc.id}</code>
                  <div>
                    {doc.state === "INDEXING" && (
                      <button
                        className="secondary"
                        disabled={busy}
                        onClick={() =>
                          void run(async () => {
                            await request(
                              `/documents/${doc.id}/retry`,
                              json({}),
                            );
                            await refresh();
                          })
                        }
                      >
                        Retry indexing
                      </button>
                    )}
                    <button
                      className="secondary"
                      disabled={busy}
                      onClick={() =>
                        void run(async () => {
                          await request(`/documents/${doc.id}`, {
                            method: "DELETE",
                          });
                          await refresh();
                          setMessage(
                            "Document deleted. Saved answer snapshots remain in history.",
                          );
                        })
                      }
                    >
                      Delete
                    </button>
                  </div>
                </li>
              ))}
            </ul>
            {!documents.length && (
              <p className="muted">
                Upload a sample from the examples folder to start.
              </p>
            )}
          </section>
        </aside>
        <section className="conversation">
          <h2>Ask your documents</h2>
          <textarea
            aria-label="Question"
            placeholder="What is the refund deadline?"
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
          />
          <div className="controls">
            <label>
              Retrieved chunks
              <select
                aria-label="Top k"
                value={topK}
                onChange={(e) => setTopK(Number(e.target.value))}
              >
                {[1, 2, 4, 6, 10].map((k) => (
                  <option key={k}>{k}</option>
                ))}
              </select>
            </label>
            <label>
              Answer style
              <select
                value={prompt}
                onChange={(e) => setPrompt(e.target.value)}
              >
                <option value="concise">Concise</option>
                <option value="detailed">Detailed</option>
              </select>
            </label>
            <button disabled={busy || !question.trim()} onClick={ask}>
              Ask question
            </button>
          </div>
          {text && (
            <article>
              <h3>Answer</h3>
              <CitedAnswer text={text} sources={sources} />
              {answer && (
                <>
                  <p className="muted">
                    Query embedding {answer.timings.embeddingMs.toFixed(1)} ms ·
                    vector HTTP retrieval{" "}
                    {answer.timings.vectorHttpMs.toFixed(1)} ms
                  </p>
                  {(answer.citations.unknownLabels.length > 0 ||
                    answer.citations.missingCitations) && (
                    <p className="notice">
                      Citation check:{" "}
                      {answer.citations.missingCitations
                        ? "No citation markers were returned."
                        : `Unknown labels: ${answer.citations.unknownLabels.join(", ")}`}
                    </p>
                  )}
                  <label>
                    Feedback comment
                    <input
                      aria-label="Feedback comment"
                      value={comment}
                      onChange={(e) => setComment(e.target.value)}
                      placeholder="What was useful or missing?"
                      maxLength={2000}
                    />
                  </label>
                  <button
                    disabled={busy || answer.status !== "COMPLETE"}
                    aria-pressed={answer.feedback?.rating === 1}
                    onClick={() => feedback(1)}
                  >
                    👍 Helpful
                  </button>
                  <button
                    disabled={busy || answer.status !== "COMPLETE"}
                    aria-pressed={answer.feedback?.rating === -1}
                    onClick={() => feedback(-1)}
                  >
                    👎 Needs work
                  </button>
                </>
              )}
            </article>
          )}
          {sources.length > 0 && (
            <div className="sources">
              <h3>Source excerpts</h3>
              {sources.map((source) => (
                <details key={source.label} id={`source-${source.label}`}>
                  <summary>
                    [{source.label}] {source.filename} · chunk{" "}
                    {source.chunkIndex + 1}
                  </summary>
                  <small>
                    Characters {source.start}–{source.end} · cosine score{" "}
                    {source.score.toFixed(3)}
                  </small>
                  <p>{source.text}</p>
                </details>
              ))}
            </div>
          )}
        </section>
      </div>
      <section>
        <h2>Review answers</h2>
        <p>
          Review saved answers and use negative feedback to investigate quality
          issues.
        </p>
        <label className="inline">
          <input
            type="checkbox"
            checked={negativeOnly}
            onChange={(e) => setNegativeOnly(e.target.checked)}
          />
          Only thumbs-down answers
        </label>
        <button className="secondary" disabled={busy} onClick={loadHistory}>
          Load history
        </button>
        <ul>
          {history.map((item) => (
            <li key={item.id}>
              <button
                className="link"
                disabled={busy}
                onClick={() => {
                  setAnswer(item);
                  setText(item.answer);
                  setSources(item.sources);
                  setQuestion(item.question);
                  setComment(item.feedback?.comment ?? "");
                }}
              >
                {item.question}
              </button>
              <span className="muted">
                {" "}
                {item.status} · {item.feedback?.comment || "No comment"}
              </span>
            </li>
          ))}
        </ul>
      </section>
      <section>
        <h2>Evaluate retrieval and answers</h2>
        <p>
          Compare fixed and paragraph chunking, top-k 2/4, and concise/detailed
          prompts. Supply your own reference answers and relevant document IDs.
        </p>
        <button className="secondary" disabled={busy} onClick={seedEvaluation}>
          Insert example case
        </button>
        <textarea
          className="json-input"
          aria-label="Evaluation cases JSON"
          value={evalCases}
          onChange={(e) => setEvalCases(e.target.value)}
          placeholder="Evaluation cases as a JSON array"
        />
        <button disabled={busy || !evalCases.trim()} onClick={evaluate}>
          Run 8 configurations
        </button>
        {evaluation && (
          <>
            <p>{evaluation.metricNote}</p>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Chunking</th>
                    <th>Size</th>
                    <th>Top-k</th>
                    <th>Prompt</th>
                    <th>Recall</th>
                    <th>MRR</th>
                    <th>Token F1</th>
                    <th>Citations</th>
                  </tr>
                </thead>
                <tbody>
                  {evaluation.results.map((row, i) => (
                    <tr key={i}>
                      <td>{row.chunking.strategy}</td>
                      <td>{row.chunking.size}</td>
                      <td>{row.topK}</td>
                      <td>{row.promptVariant}</td>
                      <td>{row.documentRecall.toFixed(2)}</td>
                      <td>{row.reciprocalRank.toFixed(2)}</td>
                      <td>{row.tokenF1.toFixed(2)}</td>
                      <td>{row.citationValidity.toFixed(2)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <button className="secondary" onClick={downloadEvaluation}>
              Download evaluation JSON
            </button>
          </>
        )}
      </section>
      <footer>
        AI Personal Knowledge Assistant · Single-user learning project · Source
        labels verify traceability, not factual correctness.
      </footer>
    </main>
  );
}

createRoot(document.getElementById("root")!).render(<App />);
