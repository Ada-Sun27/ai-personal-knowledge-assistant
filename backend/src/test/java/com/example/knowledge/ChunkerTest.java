package com.example.knowledge;

import static com.example.knowledge.Models.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ChunkerTest {
    private final Chunker chunker = new Chunker();

    @Test
    void preservesTextOffsetsAndTail() {
        String text = "A".repeat(250);
        var chunks = chunker.split("d", "f", text, new Chunking("fixed", 100, 20));
        assertEquals(3, chunks.size());
        assertEquals(160, chunks.get(2).start());
        assertEquals(250, chunks.get(2).end());
        chunks.forEach(c -> assertEquals(text.substring(c.start(), c.end()), c.text()));
    }

    @Test
    void paragraphBoundaryChangesChunks() {
        String text = "a".repeat(70) + "\n\n" + "b".repeat(80);
        var fixed = chunker.split("d", "f", text, new Chunking("fixed", 100, 0));
        var paragraphs = chunker.split("d", "f", text, new Chunking("paragraph", 100, 0));
        assertEquals(100, fixed.getFirst().end());
        assertEquals(72, paragraphs.getFirst().end());
        assertEquals(text, paragraphs.stream().map(Chunk::text).reduce("", String::concat));
    }

    @Test
    void rejectsInvalidSpecAndSkipsBlankText() {
        assertThrows(IllegalArgumentException.class, () -> new Chunking("fixed", 100, 100));
        assertThrows(IllegalArgumentException.class, () -> new Chunking("other", 100, 0));
        assertTrue(chunker.split("d", "f", "  ", new Chunking("fixed", 100, 0)).isEmpty());
    }
}
