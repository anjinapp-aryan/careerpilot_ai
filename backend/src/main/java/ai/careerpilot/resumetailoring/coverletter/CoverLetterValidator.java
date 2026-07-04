package ai.careerpilot.resumetailoring.coverletter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 2D.5 — guards the cover letter against fabrication, reusing the certification-claim
 * heuristic proven live in 2D.1 (it caught a real "Kanban Certified" hallucination in the canary):
 * a capitalized phrase followed by "certified/certification/certificate" is only allowed when it
 * appears in the candidate's evidence (original resume text + declared certifications). Also
 * enforces length bounds and that the letter actually names the target company (a letter that
 * doesn't is either generic boilerplate or addressed to the wrong job — both worth rejecting).
 *
 * <p>Deliberately NOT a full semantic fact-checker: skills/experience phrasing is constrained by
 * the prompt (which forbids fabrication) and the highest-risk, most-detectable fabrication class
 * (credentials) is checked mechanically here.
 */
@Component
public class CoverLetterValidator {

    private static final Pattern CERT_CLAIM = Pattern.compile(
            "([A-Z][A-Za-z0-9+#]*(?:\\s+[A-Z][A-Za-z0-9+#]*){0,4})\\s+(?i:certified|certification|certificate)");

    private final int minLengthChars;
    private final int maxLengthChars;

    public CoverLetterValidator(@Value("${cover.letter.min-length-chars:300}") int minLengthChars,
                                @Value("${cover.letter.max-length-chars:6000}") int maxLengthChars) {
        this.minLengthChars = minLengthChars;
        this.maxLengthChars = maxLengthChars;
    }

    public record ValidationResult(boolean valid, String reason) {
        static ValidationResult ok() { return new ValidationResult(true, null); }
        static ValidationResult fail(String reason) { return new ValidationResult(false, reason); }
    }

    public ValidationResult validate(String content, String company, String originalResumeText,
                                     List<String> declaredCertifications) {
        if (content == null || content.isBlank()) {
            return ValidationResult.fail("empty content");
        }
        String trimmed = content.trim();
        if (trimmed.length() < minLengthChars) {
            return ValidationResult.fail("too short (" + trimmed.length() + " < " + minLengthChars + " chars)");
        }
        if (trimmed.length() > maxLengthChars) {
            return ValidationResult.fail("too long (" + trimmed.length() + " > " + maxLengthChars + " chars)");
        }
        if (company != null && !company.isBlank()
                && !trimmed.toLowerCase(Locale.ROOT).contains(company.toLowerCase(Locale.ROOT))) {
            return ValidationResult.fail("does not mention the target company (" + company + ")");
        }

        List<String> fabricated = findFabricatedCertificationClaims(trimmed, originalResumeText, declaredCertifications);
        if (!fabricated.isEmpty()) {
            return ValidationResult.fail("possible fabricated certification/credential claim(s): " + fabricated);
        }
        return ValidationResult.ok();
    }

    private static List<String> findFabricatedCertificationClaims(String content, String originalResumeText,
                                                                  List<String> declaredCertifications) {
        String evidence = (originalResumeText == null ? "" : originalResumeText.toLowerCase(Locale.ROOT)) + " "
                + String.join(" ", declaredCertifications == null ? List.of() : declaredCertifications)
                        .toLowerCase(Locale.ROOT);
        List<String> fabricated = new ArrayList<>();
        Matcher m = CERT_CLAIM.matcher(content);
        while (m.find()) {
            String claim = m.group(1).trim();
            if (!evidence.contains(claim.toLowerCase(Locale.ROOT))) {
                fabricated.add(claim);
            }
        }
        return fabricated;
    }
}
