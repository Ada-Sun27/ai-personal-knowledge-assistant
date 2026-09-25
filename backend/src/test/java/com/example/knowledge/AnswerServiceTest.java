package com.example.knowledge;

import static com.example.knowledge.Models.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

class AnswerServiceTest {
    @TempDir Path temp;

    @Test
    void feedbackRetainsQuestionAnswerAndSourceSnapshotAcrossRestart() throws Exception {
        var store = new JsonStore(new ObjectMapper(), temp.toString(), "demo");
        var retrieval = mock(RetrievalService.class);
        var source =
                new Source("S1", "d:0", "d", "policy.txt", 0, 0, 25, "Refunds within 30 days.", 1);
        when(retrieval.retrieve(any(), anyString(), anyInt()))
                .thenReturn(new Retrieval(List.of(source), 1, 2, .1, .2));
        var service =
                new AnswerService(
                        new DemoGateway(), retrieval, mock(VectorClient.class), store, "demo");
        List<String> events = new ArrayList<>();
        var result =
                service.answer(
                        new Ask("Refund deadline?", 4, "concise"),
                        (event, payload) -> events.add(event));
        assertEquals("sources", events.getFirst());
        assertEquals("done", events.getLast());
        assertTrue(events.contains("token"));
        assertTrue(result.answer().contains("[S1]"));
        service.feedback(result.id(), new Rating(-1, "Too long"));
        var restarted =
                new AnswerService(
                        new DemoGateway(),
                        retrieval,
                        mock(VectorClient.class),
                        new JsonStore(new ObjectMapper(), temp.toString(), "demo"),
                        "demo");
        var negative = restarted.list(true).getFirst();
        assertEquals(result.question(), negative.question());
        assertEquals(result.answer(), negative.answer());
        assertEquals("policy.txt", negative.sources().getFirst().filename());
        assertEquals(-1, negative.feedback().rating());
    }
}
