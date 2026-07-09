package ai.careerpilot.packageintel;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 7.11 — the package gatekeeper. Deterministic (no-LLM) validation of an assembled
 * {@link ai.careerpilot.domain.ApplicationPackage} against the completeness/consistency gates that must
 * hold before any application may be submitted (Part 4). Two gate severities:
 *
 * <ul>
 *   <li><b>BLOCK</b> gates are hard invariants — a missing resume selection, a missing recommendation,
 *       or incomplete mandatory fields. Any BLOCK failure ⇒ {@link PackageValidationStatus#BLOCKED}
 *       (never auto-apply, never even human-review-then-apply until fixed).</li>
 *   <li><b>REVIEW</b> gates are soft — resume not tailored, ATS analysis absent, or (when the
 *       corresponding engine is enabled) a missing company-research / learning snapshot, or required
 *       skills not evidenced. Any REVIEW failure with no BLOCK failure ⇒
 *       {@link PackageValidationStatus#HUMAN_REVIEW}.</li>
 * </ul>
 *
 * The pure {@link #evaluate} is a total function of already-gathered booleans, so it is unit-testable
 * without any mocks. Fails safe: the {@code companyResearchRequired}/{@code learningRequired} switches
 * mean a disabled optional engine can never <em>block</em> a package on data it was never meant to
 * produce, and any unknown/absent signal degrades toward review, never toward READY.
 */
@Component
public class ApplicationPackageValidator {

    /** Severity of a single gate. */
    public enum Severity { BLOCK, REVIEW }

    /** One gate result — its name, whether it passed, and (on failure) how severe. */
    public record Check(String name, boolean passed, Severity severity) {
        public static Check ok(String name) { return new Check(name, true, Severity.REVIEW); }
        public static Check block(String name, boolean passed) { return new Check(name, passed, Severity.BLOCK); }
        public static Check review(String name, boolean passed) { return new Check(name, passed, Severity.REVIEW); }
    }

    /** Everything {@link #evaluate} needs, so it stays a pure function of the assembled package. */
    public record ValidationSignals(boolean resumeSelected, boolean resumeTailored, boolean atsAvailable,
                                    boolean recommendationAvailable, boolean companyResearchAvailable,
                                    boolean companyResearchRequired, boolean learningAvailable,
                                    boolean learningRequired, boolean requiredSkillsPresent,
                                    boolean mandatoryFieldsComplete) {}

    /** The verdict plus the per-gate detail and the first blocking reason (for the audit + Copilot). */
    public record ValidationResult(PackageValidationStatus status, List<Check> checks, String blockingReason) {}

    /**
     * Pure validation rule. Deterministic and side-effect free.
     */
    public ValidationResult evaluate(ValidationSignals s) {
        List<Check> checks = new ArrayList<>();
        // Hard gates.
        checks.add(Check.block("resume-selected", s.resumeSelected()));
        checks.add(Check.block("recommendation-available", s.recommendationAvailable()));
        checks.add(Check.block("mandatory-fields-complete", s.mandatoryFieldsComplete()));
        // Soft gates.
        checks.add(Check.review("resume-tailored", s.resumeTailored()));
        checks.add(Check.review("ats-analysis-available", s.atsAvailable()));
        checks.add(Check.review("required-skills-present", s.requiredSkillsPresent()));
        if (s.companyResearchRequired()) {
            checks.add(Check.review("company-research-available", s.companyResearchAvailable()));
        }
        if (s.learningRequired()) {
            checks.add(Check.review("learning-snapshot-available", s.learningAvailable()));
        }

        String firstBlock = firstFailure(checks, Severity.BLOCK);
        if (firstBlock != null) {
            return new ValidationResult(PackageValidationStatus.BLOCKED, checks, firstBlock);
        }
        String firstReview = firstFailure(checks, Severity.REVIEW);
        if (firstReview != null) {
            return new ValidationResult(PackageValidationStatus.HUMAN_REVIEW, checks, firstReview);
        }
        return new ValidationResult(PackageValidationStatus.READY, checks, null);
    }

    private static String firstFailure(List<Check> checks, Severity severity) {
        for (Check c : checks) {
            if (!c.passed() && c.severity() == severity) return c.name();
        }
        return null;
    }
}
