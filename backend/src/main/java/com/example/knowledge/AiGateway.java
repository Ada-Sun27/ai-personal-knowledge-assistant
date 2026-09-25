package com.example.knowledge;

import static com.example.knowledge.Models.*;

import reactor.core.publisher.Flux;

import java.util.List;

/** Provider boundary: demo is deterministic; bedrock uses real Spring AI models. */
public interface AiGateway {
    float[] embed(String text);

    Flux<String> generate(String question, List<Source> sources, String promptVariant);
}
