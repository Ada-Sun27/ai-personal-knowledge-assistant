package com.example.knowledge;

import static com.example.knowledge.Models.*;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** No LLM or AWS calls. Hash embeddings and quoted excerpts enable reproducible local tests. */
@Component
@Profile("demo")
public class DemoGateway implements AiGateway {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> STOP =
            Set.of("the", "is", "a", "an", "to", "of", "and", "in", "what", "how", "are");

    @Override
    public float[] embed(String text) {
        float[] vector = new float[1024];
        var matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            while (matcher.find()) {
                String word = matcher.group();
                if (STOP.contains(word)) continue;
                byte[] digest = hash.digest(word.getBytes(StandardCharsets.UTF_8));
                int index = ((digest[0] & 255) * 256 + (digest[1] & 255)) % vector.length;
                vector[index] += 1;
            }
            if (java.util.stream.IntStream.range(0, vector.length).allMatch(i -> vector[i] == 0))
                vector[0] = 1;
            return vector;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Flux<String> generate(String question, List<Source> sources, String variant) {
        if (sources.isEmpty())
            return Flux.just("I couldn't find supporting information in the indexed documents.");
        int limit = "concise".equals(variant) ? 1 : Math.min(3, sources.size());
        StringBuilder answer = new StringBuilder("Demo extractive answer (no LLM):\n");
        for (int i = 0; i < limit; i++) {
            Source source = sources.get(i);
            answer.append(source.text().strip()).append(" [").append(source.label()).append("]\n");
        }
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < answer.length(); i += 32)
            tokens.add(answer.substring(i, Math.min(i + 32, answer.length())));
        return Flux.fromIterable(tokens);
    }
}
