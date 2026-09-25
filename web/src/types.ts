export type Chunking = {
  strategy: "fixed" | "paragraph";
  size: number;
  overlap: number;
};
export type Document = {
  id: string;
  filename: string;
  chunks: number;
  state: string;
  chunking: Chunking;
};
export type Source = {
  label: string;
  id: string;
  documentId: string;
  filename: string;
  chunkIndex: number;
  start: number;
  end: number;
  text: string;
  score: number;
};
export type Timings = {
  embeddingMs: number;
  vectorHttpMs: number;
  searchMs: number;
  serviceMs: number;
};
export type Answer = {
  id: string;
  question: string;
  answer: string;
  sources: Source[];
  mode: string;
  citations: { unknownLabels: string[]; missingCitations: boolean };
  timings: Timings;
  status: string;
  feedback: { rating: number; comment: string } | null;
};
export type EvalRow = {
  chunking: Chunking;
  topK: number;
  promptVariant: string;
  documentRecall: number;
  documentPrecision: number;
  reciprocalRank: number;
  tokenF1: number;
  citationValidity: number;
};
export type Evaluation = {
  id: string;
  mode: string;
  results: EvalRow[];
  metricNote: string;
};
