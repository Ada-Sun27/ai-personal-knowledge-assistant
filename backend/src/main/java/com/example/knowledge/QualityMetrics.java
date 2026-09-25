package com.example.knowledge;

import static com.example.knowledge.Models.*;

import java.util.*;
import java.util.regex.Pattern;

/** Transparent proxy metrics, not a claim of factual entailment or human-level judging. */
public final class QualityMetrics {
    /** Matches [S1] and grouped markers such as [S1, S3]. */
    private static final Pattern CITATION = Pattern.compile("\\[(S\\d+(?:\\s*,\\s*S\\d+)*)\\]");

    private QualityMetrics() {}

    public static CitationCheck citations(String answer, List<Source> sources) {
        Set<String> valid = new HashSet<>();
        sources.forEach(s -> valid.add(s.label()));
        Set<String> cited = new LinkedHashSet<>();
        var matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            for (String label : matcher.group(1).split(",")) cited.add(label.strip());
        }
        List<String> unknown = cited.stream().filter(c -> !valid.contains(c)).toList();
        return new CitationCheck(
                List.copyOf(cited), unknown, !sources.isEmpty() && cited.isEmpty());
    }

    public static double tokenF1(String answer, String reference) {
        var actual = counts(CITATION.matcher(answer).replaceAll(""));
        var expected = counts(reference);
        int shared =
                actual.entrySet().stream()
                        .mapToInt(e -> Math.min(e.getValue(), expected.getOrDefault(e.getKey(), 0)))
                        .sum();
        int a = actual.values().stream().mapToInt(Integer::intValue).sum();
        int b = expected.values().stream().mapToInt(Integer::intValue).sum();
        return a + b == 0 ? 1 : 2.0 * shared / (a + b);
    }

    private static Map<String, Integer> counts(String value) {
        Map<String, Integer> result = new HashMap<>();
        for (String word : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isBlank()) result.merge(word, 1, Integer::sum);
        }
        return result;
    }
}
