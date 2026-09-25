package com.example.knowledge;

import static com.example.knowledge.Models.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

class DocumentServiceTest {
    @TempDir Path temp;

    @Test
    void failedIngestionCanBeRetriedIdempotently() throws Exception {
        var store = new JsonStore(new ObjectMapper(), temp.toString(), "demo");
        var vector = mock(VectorClient.class);
        var service = new DocumentService(store, new Chunker(), new DemoGateway(), vector);
        doThrow(new IllegalStateException("network"))
                .doNothing()
                .when(vector)
                .replaceDocument(any(), anyString(), anyList());
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.ingest(
                                new MockMultipartFile(
                                        "file",
                                        "a.txt",
                                        "text/plain",
                                        "Refund window is 30 days".getBytes()),
                                new Chunking("fixed", 100, 0)));
        var pending = service.list().getFirst();
        assertEquals("INDEXING", pending.state());
        assertEquals("READY", service.retry(pending.id()).state());
        service.delete(pending.id());
        assertTrue(service.list().isEmpty());
    }

    @Test
    void extractsPdfAndRejectsNonUtf8() throws Exception {
        var service =
                new DocumentService(
                        new JsonStore(new ObjectMapper(), temp.toString(), "demo"),
                        new Chunker(),
                        new DemoGateway(),
                        mock(VectorClient.class));
        try (PDDocument pdf = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText("Refund deadline: 30 days.");
                content.endText();
            }
            pdf.save(out);
            assertTrue(service.extract("policy.pdf", out.toByteArray()).contains("30 days"));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> service.extract("bad.txt", new byte[] {(byte) 0xff}));
    }
}
