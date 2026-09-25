package com.example.knowledge;

import static com.example.knowledge.Models.*;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class Chunker {
    /** Offsets always refer to the extracted document text, including whitespace. */
    public List<Chunk> split(String documentId, String filename, String text, Chunking spec) {
        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + spec.size(), text.length());
            if ("paragraph".equals(spec.strategy()) && end < text.length()) {
                int boundary = text.lastIndexOf("\n\n", end - 1);
                if (boundary > start + spec.size() / 3) end = boundary + 2;
            }
            String content = text.substring(start, end);
            if (!content.isBlank()) {
                if (chunks.size() >= 2000)
                    throw new IllegalArgumentException(
                            "Too many chunks; increase size or reduce overlap");
                int index = chunks.size();
                chunks.add(
                        new Chunk(
                                documentId + ":" + index,
                                documentId,
                                filename,
                                index,
                                start,
                                end,
                                content));
            }
            if (end == text.length()) break;
            // A short paragraph can be shorter than overlap: always make forward progress.
            start = Math.max(start + 1, end - spec.overlap());
        }
        return chunks;
    }
}
