package ai.careerpilot.jobdiscovery.enrich;

import ai.careerpilot.ai.AiGatewayService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Parse/validate guards for the LLM job-enrichment extraction — the failure modes that matter when
 * an LLM returns fenced, prose-prefixed, or malformed output. Exercises {@code parseAndValidate}
 * directly (no AI call needed).
 */
class JobAiEnrichmentExtractorTest {

    private final JobAiEnrichmentExtractor extractor =
            new JobAiEnrichmentExtractor(mock(AiGatewayService.class), 6000, java.util.List.of("gemini"));

    private static final String VALID = """
            {
              "seniorityLevel": "Senior",
              "normalizedSkills": ["React", "TypeScript", "Node.js"],
              "domains": ["Fintech"],
              "employmentType": "Full-time",
              "salaryBandMin": 90000,
              "salaryBandMax": 130000,
              "salaryCurrency": "USD",
              "salaryEstimated": true,
              "summary": "Senior frontend role building fintech dashboards.",
              "confidenceScore": 0.82
            }
            """;

    @Test
    void parsesCleanJson() {
        JobEnrichmentResult r = extractor.parseAndValidate(VALID);
        assertEquals("Senior", r.seniorityLevel());
        assertEquals(3, r.normalizedSkills().size());
        assertTrue(r.normalizedSkills().contains("TypeScript"));
        assertEquals("Fintech", r.domains().get(0));
        assertEquals("Full-time", r.employmentType());
        assertEquals(0, new BigDecimal("90000").compareTo(r.salaryBandMin()));
        assertEquals(0, new BigDecimal("130000").compareTo(r.salaryBandMax()));
        assertEquals("USD", r.salaryCurrency());
        assertTrue(r.salaryEstimated());
        assertEquals(0, new BigDecimal("0.82").compareTo(r.confidenceScore()));
    }

    @Test
    void stripsMarkdownFences() {
        String fenced = "```json\n" + VALID + "\n```";
        JobEnrichmentResult r = extractor.parseAndValidate(fenced);
        assertEquals("Senior", r.seniorityLevel());
    }

    @Test
    void salvagesProsePrefixedJson() {
        String prefixed = "Here is the enrichment you asked for:\n" + VALID + "\nHope that helps!";
        JobEnrichmentResult r = extractor.parseAndValidate(prefixed);
        assertEquals(3, r.normalizedSkills().size());
    }

    @Test
    void clampsConfidenceOutOfRange() {
        JobEnrichmentResult r = extractor.parseAndValidate("""
                {"seniorityLevel":"Mid","normalizedSkills":["Go"],"confidenceScore": 9.9}
                """);
        assertEquals(0, BigDecimal.ONE.compareTo(r.confidenceScore()));
    }

    @Test
    void salaryFieldsTolerateNulls() {
        JobEnrichmentResult r = extractor.parseAndValidate("""
                {"seniorityLevel":"Junior","normalizedSkills":["SQL"],
                 "salaryBandMin": null, "salaryBandMax": null, "salaryCurrency": null,
                 "salaryEstimated": null}
                """);
        assertNull(r.salaryBandMin());
        assertNull(r.salaryBandMax());
        assertNull(r.salaryCurrency());
        assertNull(r.salaryEstimated());
    }

    @Test
    void rejectsResponseWithNeitherSeniorityNorSkills() {
        assertThrows(JobEnrichmentException.class, () -> extractor.parseAndValidate("""
                {"domains":["Retail"],"summary":"A role.","confidenceScore":0.5}
                """));
    }

    @Test
    void rejectsNonJson() {
        assertThrows(JobEnrichmentException.class,
                () -> extractor.parseAndValidate("I could not analyze this posting."));
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(JobEnrichmentException.class,
                () -> extractor.parseAndValidate("{ \"seniorityLevel\": \"Senior\", "));
    }
}
