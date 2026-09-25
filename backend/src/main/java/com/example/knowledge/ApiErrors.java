package com.example.knowledge;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.*;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class
    })
    public ResponseEntity<Map<String, String>> invalid(Exception error) {
        String message =
                error instanceof MethodArgumentNotValidException
                        ? "Invalid request fields"
                        : error.getMessage();
        return ResponseEntity.badRequest()
                .body(Map.of("error", message == null ? "Invalid request" : message));
    }

    @ExceptionHandler(java.io.IOException.class)
    public ResponseEntity<Map<String, String>> unreadable(Exception error) {
        return ResponseEntity.badRequest()
                .body(
                        Map.of(
                                "error",
                                "Document could not be read; check file format and PDF"
                                    + " permissions"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> missing(Exception error) {
        return ResponseEntity.status(404).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge() {
        return ResponseEntity.status(413).body(Map.of("error", "Maximum document size is 10 MB"));
    }

    @ExceptionHandler({RestClientException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> upstream(Exception error) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Provider or index failure", error);
        return ResponseEntity.status(502)
                .body(
                        Map.of(
                                "error",
                                "Provider or index failure; inspect the server log and retry"));
    }
}
