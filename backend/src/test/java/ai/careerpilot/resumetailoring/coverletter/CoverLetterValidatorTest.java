package ai.careerpilot.resumetailoring.coverletter;

import ai.careerpilot.resumetailoring.coverletter.CoverLetterValidator.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2D.5 — {@link CoverLetterValidator}: length bounds, target-company presence, and the
 * fabricated-credential heuristic proven live in the 2D.1 canary.
 */
class CoverLetterValidatorTest {

    private final CoverLetterValidator validator = new CoverLetterValidator(100, 6000);

    private static final String RESUME = "Backend engineer with 8 years of Java and Spring Boot. "
            + "AWS Certified Solutions Architect. Led a team of four at DataCorp.";

    private String validLetter() {
        return "Dear Hiring Team at Acme, I am excited to apply for the Backend Engineer role. "
                + "My eight years of Java and Spring Boot experience, including leading a team of four, "
                + "align directly with your needs. I would welcome the chance to discuss how I can "
                + "contribute to Acme's platform. Sincerely, A Candidate.";
    }

    @Test
    void aGroundedLetterPasses() {
        ValidationResult result = validator.validate(validLetter(), "Acme", RESUME, List.of());
        assertTrue(result.valid());
    }

    @Test
    void rejectsTooShortContent() {
        ValidationResult result = validator.validate("Too short.", "Acme", RESUME, List.of());
        assertFalse(result.valid());
        assertTrue(result.reason().contains("too short"));
    }

    @Test
    void rejectsContentThatNeverMentionsTheTargetCompany() {
        String letter = validLetter().replace("Acme", "SomeOtherCorp");
        ValidationResult result = validator.validate(letter, "Acme", RESUME, List.of());
        assertFalse(result.valid());
        assertTrue(result.reason().contains("does not mention the target company"));
    }

    @Test
    void rejectsAFabricatedCertificationClaim() {
        String letter = validLetter() + " I am also a Kubernetes Certified administrator.";
        ValidationResult result = validator.validate(letter, "Acme", RESUME, List.of());
        assertFalse(result.valid());
        assertTrue(result.reason().contains("Kubernetes"));
    }

    @Test
    void allowsACertificationTheResumeOrProfileActuallyDeclares() {
        String letter = validLetter() + " As an AWS Certified Solutions Architect I bring cloud depth.";
        ValidationResult result = validator.validate(letter, "Acme", RESUME, List.of("AWS Certified Solutions Architect"));
        assertTrue(result.valid());
    }

    @Test
    void rejectsEmptyContent() {
        assertFalse(validator.validate(null, "Acme", RESUME, List.of()).valid());
        assertFalse(validator.validate("   ", "Acme", RESUME, List.of()).valid());
    }
}
