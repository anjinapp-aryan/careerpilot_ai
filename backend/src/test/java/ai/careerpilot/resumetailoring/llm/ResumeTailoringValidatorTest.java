package ai.careerpilot.resumetailoring.llm;

import ai.careerpilot.resumetailoring.llm.ResumeTailoringValidator.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2D.1 Step 7 — the validator is the deterministic backstop for "never invent
 * skills/certifications/companies": it must reject size violations and fabricated-sounding
 * certification claims, while never false-positiving on a resume that only reorganizes real facts.
 */
class ResumeTailoringValidatorTest {

    private static final String ORIGINAL = "Senior Backend Engineer with 8 years experience in Java, Spring Boot, "
            + "AWS, and Kubernetes. AWS Certified Solutions Architect. Led a team of 5 engineers at Acme Corp.";

    /** Realistic 800-char default, for the length-boundary tests. */
    private final ResumeTailoringValidator validator = new ResumeTailoringValidator(800, 20000);
    /** Lower threshold so the semantic (anti-hallucination) tests aren't confounded by length. */
    private final ResumeTailoringValidator semanticValidator = new ResumeTailoringValidator(50, 20000);

    @Test
    void rejectsTailoredTextBelowMinimumLength() {
        ValidationResult result = validator.validate("too short", ORIGINAL,
                List.of("Java"), List.of(), List.of());
        assertFalse(result.valid());
        assertTrue(result.reason().contains("too short"));
    }

    @Test
    void rejectsTailoredTextAboveMaximumLength() {
        ResumeTailoringValidator tight = new ResumeTailoringValidator(10, 50);
        String tooLong = "x".repeat(60);
        ValidationResult result = tight.validate(tooLong, ORIGINAL, List.of(), List.of(), List.of());
        assertFalse(result.valid());
        assertTrue(result.reason().contains("too long"));
    }

    @Test
    void rejectsEmptyTailoredText() {
        assertFalse(validator.validate(null, ORIGINAL, List.of(), List.of(), List.of()).valid());
        assertFalse(validator.validate("   ", ORIGINAL, List.of(), List.of(), List.of()).valid());
    }

    @Test
    void acceptsResumeThatOnlyReordersRealFactsFromTheOriginal() {
        String tailored = "Senior Backend Engineer tailored for a Platform role. 8 years experience in "
                + "Kubernetes, AWS, Spring Boot, and Java. AWS Certified Solutions Architect. Led a team of 5 "
                + "engineers at Acme Corp, focusing on platform reliability and cloud infrastructure.";
        ValidationResult result = semanticValidator.validate(tailored, ORIGINAL,
                List.of("Java", "Spring Boot", "AWS", "Kubernetes"), List.of(), List.of("AWS Certified Solutions Architect"));
        assertTrue(result.valid(), result.reason());
    }

    @Test
    void rejectsAFabricatedCertificationNotInOriginalOrDeclaredList() {
        String tailored = "Senior Backend Engineer with deep expertise. Google Cloud Professional Architect "
                + "Certified. Extensive experience across distributed systems and platform engineering teams.";
        ValidationResult result = semanticValidator.validate(tailored, ORIGINAL,
                List.of("Java"), List.of(), List.of("AWS Certified Solutions Architect"));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("certification"));
    }

    @Test
    void aDeclaredCertificationMentionedInTailoredTextIsNeverFlagged() {
        String tailored = "Backend engineer skilled across cloud platforms. Google Cloud Professional Architect "
                + "Certified with hands-on delivery experience across several large scale platform migrations.";
        ValidationResult result = semanticValidator.validate(tailored, ORIGINAL,
                List.of("Java"), List.of(), List.of("Google Cloud Professional Architect Certified"));
        assertTrue(result.valid(), result.reason());
    }
}
