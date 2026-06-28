package ai.careerpilot.service.profile;

import ai.careerpilot.ai.AiGatewayService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Parse/validate guards for the AI extraction — the failure modes that matter when an LLM
 * returns fenced, prose-prefixed, or malformed output. Exercises {@code parseAndValidate}
 * directly (no AI call needed).
 */
class CandidateProfileExtractorTest {

    private final CandidateProfileExtractor extractor =
            new CandidateProfileExtractor(mock(AiGatewayService.class), 12000);

    private static final String VALID = """
            {
              "yearsExperience": 12,
              "currentRole": "Senior Java Developer",
              "seniority": "Architect",
              "skills": ["Java", "Spring Boot", "AWS"],
              "targetRoles": ["Solution Architect", "Tech Lead"],
              "domains": ["Finance"],
              "languages": ["English"],
              "profileSummary": "Experienced backend engineer.",
              "confidenceScore": 0.86
            }
            """;

    @Test
    void parsesCleanJson() {
        ResumeIntelligence ri = extractor.parseAndValidate(VALID);
        assertEquals(12, ri.yearsExperience());
        assertEquals("Senior Java Developer", ri.currentRole());
        assertEquals("Architect", ri.seniority());
        assertEquals(3, ri.skills().size());
        assertTrue(ri.targetRoles().contains("Tech Lead"));
        assertEquals(0, BigDecimal.valueOf(0.86).compareTo(ri.confidenceScore()));
    }

    @Test
    void stripsMarkdownFences() {
        String fenced = "```json\n" + VALID + "\n```";
        assertEquals("Architect", extractor.parseAndValidate(fenced).seniority());
    }

    @Test
    void salvagesJsonWithPreambleProse() {
        String noisy = "Sure! Here is the extraction:\n" + VALID + "\nHope this helps.";
        assertEquals("Senior Java Developer", extractor.parseAndValidate(noisy).currentRole());
    }

    @Test
    void clampsConfidenceToUnitInterval() {
        String over = VALID.replace("0.86", "9.9");
        assertEquals(0, BigDecimal.ONE.compareTo(extractor.parseAndValidate(over).confidenceScore()));
    }

    @Test
    void coercesStringYearsToInt() {
        String s = VALID.replace("\"yearsExperience\": 12", "\"yearsExperience\": \"12 years\"");
        assertEquals(12, extractor.parseAndValidate(s).yearsExperience());
    }

    @Test
    void throwsOnNonJson() {
        assertThrows(ProfileExtractionException.class,
                () -> extractor.parseAndValidate("the model refused"));
    }

    @Test
    void throwsWhenNeitherRoleNorSkills() {
        String empty = """
                {"currentRole": null, "skills": [], "confidenceScore": 0.2}
                """;
        assertThrows(ProfileExtractionException.class, () -> extractor.parseAndValidate(empty));
    }

    @Test
    void extractRejectsEmptyResume() {
        assertThrows(ProfileExtractionException.class, () -> extractor.extract("   "));
    }
}
