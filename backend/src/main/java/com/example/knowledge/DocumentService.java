package com.example.knowledge;

import static com.example.knowledge.Models.*;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;

@Service
public class DocumentService {
    private final JsonStore store;
    private final Chunker chunker;
    private final AiGateway ai;
    private final VectorClient vectors;

    public DocumentService(JsonStore store, Chunker chunker, AiGateway ai, VectorClient vectors) {
        this.store = store;
        this.chunker = chunker;
        this.ai = ai;
        this.vectors = vectors;
    }

    public synchronized DocumentSummary ingest(MultipartFile file, Chunking spec)
            throws IOException {
        String name =
                Optional.ofNullable(file.getOriginalFilename())
                        .orElse("document.txt")
                        .replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.length() > 200) throw new IllegalArgumentException("Filename is too long");
        String text = extract(name, file.getBytes());
        if (text.isBlank())
            throw new IllegalArgumentException(
                    "Document contains no extractable text (scanned PDFs need OCR)");
        if (text.length() > 500_000)
            throw new IllegalArgumentException("Extracted text exceeds 500,000 characters");
        String id = UUID.randomUUID().toString();
        var chunks = chunker.split(id, name, text, spec);
        if (chunks.size() > 2000)
            throw new IllegalArgumentException("Too many chunks; increase chunk size");
        var document =
                new DocumentRecord(
                        id, name, text, spec, chunks.size(), "INDEXING", Instant.now().toString());
        store.put("documents", id, document);
        try {
            index(vectors.mainCollection, document, spec);
            document =
                    new DocumentRecord(
                            id, name, text, spec, chunks.size(), "READY", document.createdAt());
            store.put("documents", id, document);
            return summary(document);
        } catch (RuntimeException error) {
            // Durable INDEXING record permits explicit recovery after provider/network failures.
            throw new IllegalStateException(
                    "Indexing failed; use document retry for ID " + id, error);
        }
    }

    String extract(String filename, byte[] bytes) throws IOException {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            try (var pdf = Loader.loadPDF(bytes)) {
                return new PDFTextStripper().getText(pdf);
            }
        }
        if (!(lower.endsWith(".txt") || lower.endsWith(".md")))
            throw new IllegalArgumentException("Use .txt, .md or .pdf");
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                    .replace("\r\n", "\n");
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Text documents must use UTF-8");
        }
    }

    public List<VectorChunk> embed(DocumentRecord document, Chunking spec) {
        return chunker.split(document.id(), document.filename(), document.text(), spec).stream()
                .map(
                        c ->
                                new VectorChunk(
                                        c.id(),
                                        c.documentId(),
                                        c.filename(),
                                        c.chunkIndex(),
                                        c.start(),
                                        c.end(),
                                        c.text(),
                                        ai.embed(c.text())))
                .toList();
    }

    public void index(String collection, DocumentRecord document, Chunking spec) {
        vectors.replaceDocument(collection, document.id(), embed(document, spec));
    }

    public synchronized DocumentSummary retry(String id) {
        var d = get(id);
        if ("DELETING".equals(d.state()))
            throw new IllegalArgumentException("Retry deletion instead");
        index(vectors.mainCollection, d, d.chunking());
        var ready =
                new DocumentRecord(
                        d.id(),
                        d.filename(),
                        d.text(),
                        d.chunking(),
                        d.chunks(),
                        "READY",
                        d.createdAt());
        store.put("documents", id, ready);
        return summary(ready);
    }

    public synchronized void delete(String id) {
        var d = get(id);
        store.put(
                "documents",
                id,
                new DocumentRecord(
                        d.id(),
                        d.filename(),
                        d.text(),
                        d.chunking(),
                        d.chunks(),
                        "DELETING",
                        d.createdAt()));
        vectors.deleteDocument(vectors.mainCollection, id);
        store.delete("documents", id);
    }

    public DocumentRecord get(String id) {
        return store.get("documents", id, DocumentRecord.class)
                .orElseThrow(() -> new NoSuchElementException("Document not found"));
    }

    public List<DocumentRecord> ready() {
        return store.list("documents", DocumentRecord.class).stream()
                .filter(d -> "READY".equals(d.state()))
                .toList();
    }

    public List<DocumentSummary> list() {
        return store.list("documents", DocumentRecord.class).stream().map(this::summary).toList();
    }

    private DocumentSummary summary(DocumentRecord d) {
        return new DocumentSummary(
                d.id(), d.filename(), d.chunking(), d.chunks(), d.state(), d.createdAt());
    }
}
