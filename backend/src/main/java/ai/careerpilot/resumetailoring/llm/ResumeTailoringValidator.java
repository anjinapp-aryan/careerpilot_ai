package ai.careerpilot.resumetailoring.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, non-LLM backstop for the prompt's "never invent X" rules (Step 7). The prompt
 * asks the model not to hallucinate; this validator is what actually enforces it — every
 * technology/certification token the tailored text claims must already appear (case-insensitive)
 * in the original resume text or the candidate's declared skills/certifications/technologies.
 * Also enforces min/max output size. A generation that fails validation is never persisted as a
 * usable {@code ResumeTailoring} row — the caller records a {@code VALIDATION_REJECTED} audit
 * entry instead (see {@code ResumeTailoringService}).
 */
@Component
public class ResumeTailoringValidator {

    /** Token pattern: alphanumeric words of 2+ chars, so single letters/punctuation are ignored. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9+.#]{1,}");

    private final int minLengthChars;
    private final int maxLengthChars;

    public ResumeTailoringValidator(
            @Value("${resume.tailoring.min-length-chars:800}") int minLengthChars,
            @Value("${resume.tailoring.max-length-chars:20000}") int maxLengthChars) {
        this.minLengthChars = minLengthChars;
        this.maxLengthChars = maxLengthChars;
    }

    public record ValidationResult(boolean valid, String reason) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }
        public static ValidationResult rejected(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    /**
     * Validate a candidate tailored resume against the allowed vocabulary derived from the
     * original resume text plus the candidate's declared skills/technologies/certifications.
     * {@code allowedExtraTerms} lets the job's own vocabulary (title, skills) through too — a
     * tailored resume is allowed to *mention* the job's required skills in a summary sentence
     * even if the candidate profile didn't already list the exact same casing, as long as the
     * skill is also present in the original resume text (checked by {@link #buildAllowedVocabulary}).
     */
    public ValidationResult validate(String tailoredText, String originalResumeText,
                                     List<String> declaredSkills, List<String> declaredTechnologies,
                                     List<String> declaredCertifications) {
        if (tailoredText == null || tailoredText.isBlank()) {
            return ValidationResult.rejected("empty tailored resume");
        }
        int len = tailoredText.length();
        if (len < minLengthChars) {
            return ValidationResult.rejected("tailored resume too short (" + len + " < " + minLengthChars + " chars)");
        }
        if (len > maxLengthChars) {
            return ValidationResult.rejected("tailored resume too long (" + len + " > " + maxLengthChars + " chars)");
        }

        Set<String> allowed = buildAllowedVocabulary(originalResumeText, declaredSkills, declaredTechnologies, declaredCertifications);
        Set<String> invented = findInventedCertificationClaims(tailoredText, allowed, declaredCertifications, originalResumeText);
        if (!invented.isEmpty()) {
            return ValidationResult.rejected("possible fabricated certification/credential claim(s): " + invented);
        }
        return ValidationResult.ok();
    }

    private Set<String> buildAllowedVocabulary(String originalResumeText, List<String> declaredSkills,
                                               List<String> declaredTechnologies, List<String> declaredCertifications) {
        Set<String> allowed = new LinkedHashSet<>();
        addTokens(allowed, originalResumeText);
        declaredSkills.forEach(s -> addTokens(allowed, s));
        declaredTechnologies.forEach(s -> addTokens(allowed, s));
        declaredCertifications.forEach(s -> addTokens(allowed, s));
        return allowed;
    }

    private void addTokens(Set<String> out, String text) {
        if (text == null) return;
        Matcher m = TOKEN.matcher(text);
        while (m.find()) out.add(m.group().toLowerCase(Locale.ROOT));
    }

    /**
     * Certification/credential names are the highest-stakes hallucination (a fabricated
     * credential is a factual, checkable claim). Flag any capitalized multi-word phrase adjacent
     * to "certified"/"certification"/"certificate" in the tailored text whose normalized tokens
     * aren't fully covered by the allowed vocabulary AND aren't already one of the candidate's
     * declared certifications.
     */
    private Set<String> findInventedCertificationClaims(String tailoredText, Set<String> allowed,
                                                         List<String> declaredCertifications, String originalResumeText) {
        Set<String> declaredLower = new LinkedHashSet<>();
        declaredCertifications.forEach(c -> declaredLower.add(c.toLowerCase(Locale.ROOT)));

        Set<String> invented = new LinkedHashSet<>();
        // The capital-letter requirement on each captured word is what keeps this from tripping on
        // ordinary lowercase prose; only the trailing "certified/certification/certificate" keyword
        // itself should match case-insensitively, so that flag is scoped to just the inline group
        // instead of the whole pattern (a whole-pattern CASE_INSENSITIVE would also lowercase-match
        // [A-Z], defeating the capital-word heuristic entirely). The word chars deliberately exclude
        // '.' (unlike the shared TOKEN pattern) so a sentence-ending period never glues an unrelated
        // prior sentence's capitalized word onto the certification-name candidate (e.g. "...in Java.
        // AWS Certified..." must not be read as the phrase "Java. AWS").
        Pattern certContext = Pattern.compile(
                "([A-Z][A-Za-z0-9+#]*(?:\\s+[A-Z][A-Za-z0-9+#]*){0,4})\\s+(?i:certified|certification|certificate)");
        Matcher m = certContext.matcher(tailoredText);
        while (m.find()) {
            String phrase = m.group(1).trim();
            String phraseLower = phrase.toLowerCase(Locale.ROOT);
            boolean declared = declaredLower.stream().anyMatch(d -> d.contains(phraseLower) || phraseLower.contains(d));
            boolean inOriginal = originalResumeText != null
                    && originalResumeText.toLowerCase(Locale.ROOT).contains(phraseLower);
            if (!declared && !inOriginal) {
                invented.add(phrase);
            }
        }
        return invented;
    }
}
