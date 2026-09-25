package com.example.knowledge;

import static com.example.knowledge.Models.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class QualityMetricsTest {
    private final Source source = new Source("S1", "d:0", "d", "notes", 0, 0, 3, "abc", 1);

    @Test
    void detectsUnknownAndMissingCitations() {
        var report = QualityMetrics.citations("Claim [S1] and [S9]", List.of(source));
        assertEquals(List.of("S9"), report.unknownLabels());
        assertFalse(report.missingCitations());
        assertTrue(QualityMetrics.citations("Uncited answer", List.of(source)).missingCitations());
    }

    @Test
    void parsesGroupedCitationMarkers() {
        var second = new Source("S2", "d:1", "d", "notes", 1, 3, 6, "def", 1);
        var report = QualityMetrics.citations("Claim [S1, S2]. Other [S2,S7].", List.of(source, second));
        assertEquals(List.of("S1", "S2", "S7"), report.citedLabels());
        assertEquals(List.of("S7"), report.unknownLabels());
        assertFalse(report.missingCitations());
        assertEquals(1, QualityMetrics.tokenF1("Thirty days [S1, S2]", "thirty days"));
    }

    @Test
    void computesReferenceF1WithoutCitationTokens() {
        assertEquals(1, QualityMetrics.tokenF1("Refunds: 30 days [S1]", "refunds 30 days"));
        assertEquals(0, QualityMetrics.tokenF1("apples", "oranges"));
        assertEquals(.5, QualityMetrics.tokenF1("one one two", "two"));
    }
}
