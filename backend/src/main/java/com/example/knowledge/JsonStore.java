package com.example.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;

/**
 * Atomic, single-process JSON persistence. Separate profile roots prevent embedding-space mixing.
 */
@Component
public class JsonStore {
    private final Path root;
    private final ObjectMapper mapper;

    public JsonStore(
            ObjectMapper mapper,
            @Value("${app.data-dir}") String directory,
            @Value("${app.mode}") String mode)
            throws IOException {
        this.mapper = mapper;
        root = Path.of(directory, mode);
        for (String kind : List.of("documents", "answers", "evaluations"))
            Files.createDirectories(root.resolve(kind));
    }

    private Path path(String kind, String id) {
        if (!List.of("documents", "answers", "evaluations").contains(kind)
                || !id.matches("[A-Za-z0-9-]+"))
            throw new IllegalArgumentException("Invalid record ID");
        return root.resolve(kind).resolve(id + ".json");
    }

    public synchronized void put(String kind, String id, Object value) {
        Path target = path(kind, id);
        Path temporary = null;
        try {
            temporary = Files.createTempFile(target.getParent(), "write-", ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (temporary != null)
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
        }
    }

    public synchronized <T> Optional<T> get(String kind, String id, Class<T> type) {
        Path path = path(kind, id);
        if (!Files.exists(path)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(path.toFile(), type));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized <T> List<T> list(String kind, Class<T> type) {
        try (var paths = Files.list(root.resolve(kind))) {
            return paths.filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .map(
                            p -> {
                                try {
                                    return mapper.readValue(p.toFile(), type);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void delete(String kind, String id) {
        try {
            Files.deleteIfExists(path(kind, id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
