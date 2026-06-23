package ai.careerpilot.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Plain unit tests for the optimized-resume document renderer (no Spring context). */
class ResumeDocumentServiceTest {

    private final ResumeDocumentService svc = new ResumeDocumentService();

    private Map<String, Object> sampleOptimized() {
        return Map.of(
                "executive_summary", "Senior engineer with 10 years building distributed systems.",
                "professional_experience", List.of(
                        "Led migration of monolith to microservices, cutting latency 40%.",
                        "Mentored 6 engineers; introduced CI/CD reducing deploy time 70%."),
                "skills_section", List.of("Java", "Spring Boot", "Kubernetes", "Kafka"),
                "full_markdown", "# Jane Doe\n\nSenior Engineer\n\n## Summary\n...");
    }

    @Test
    void toDocx_producesValidOpenableDocument() throws Exception {
        byte[] docx = svc.toDocx(sampleOptimized());
        assertNotNull(docx, "DOCX bytes should be produced");
        assertTrue(docx.length > 0);
        // POI must be able to re-open the bytes — proves it is a valid .docx, not garbage.
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            String text = String.join("\n", doc.getParagraphs().stream().map(p -> p.getText()).toList());
            assertTrue(text.contains("Executive Summary"));
            assertTrue(text.contains("Kubernetes"));
        }
    }

    @Test
    void toDocx_returnsNullForEmptyInput() {
        assertNull(svc.toDocx(Map.of()));
        assertNull(svc.toDocx(null));
    }

    @Test
    void toPlainText_prefersFullMarkdown() {
        String text = svc.toPlainText(sampleOptimized());
        assertTrue(text.startsWith("# Jane Doe"));
    }

    @Test
    void toPlainText_assemblesSectionsWhenNoMarkdown() {
        Map<String, Object> noMd = Map.of(
                "executive_summary", "Concise summary.",
                "skills_section", List.of("Go", "Rust"));
        String text = svc.toPlainText(noMd);
        assertTrue(text.contains("EXECUTIVE SUMMARY"));
        assertTrue(text.contains("Concise summary."));
        assertTrue(text.contains("Go, Rust"));
    }
}
