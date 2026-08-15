package ai.careerpilot.jobdiscovery;

import org.springframework.stereotype.Component;

/**
 * International Job Discovery Phase 2 — deterministic, keyword-based job-industry classification.
 * Same discipline as {@link VisaSignalClassifier}: pure, no I/O, no LLM call per job, and never
 * infers from anything but the job's own text (title/description/company) — country is never
 * consulted here, so "Germany" alone can never resolve to BANKING.
 *
 * <p>Reused for two purposes: (1) a job's own industry, for badges/scoring, and (2) — via {@link
 * ai.careerpilot.jobdiscovery.international.CandidateCountryFitClassifier}, which classifies the
 * concatenation of the candidate's target role + skills through this same method — the
 * candidate's own inferred industry affinity, so the two "does this look like banking" questions
 * share one rule set rather than two.
 */
@Component
public class IndustryFitClassifier {

    private static final String[] BANKING_PHRASES = {
            "j.p. morgan", "jpmorgan", "jp morgan", "goldman sachs", "morgan stanley",
            "deutsche bank", "barclays", "bnp paribas", "asset servicing", "capital markets",
            "investment banking", "core banking", "securities", "trading system", "trading platform",
    };
    private static final String[] FINTECH_PHRASES = {
            "fintech", "payments", "payment processing", "digital banking", "neobank", "open banking",
    };
    private static final String[] CLOUD_PHRASES = {
            "aws", "eks", "ec2", "cloudformation", "terraform", "cloud migration", "cloud architecture",
            "azure", "gcp", "google cloud",
    };
    private static final String[] PLATFORM_PHRASES = {
            "platform engineering", "developer platform", "internal platform", "kubernetes",
            "distributed systems", "microservices platform",
    };
    private static final String[] ENTERPRISE_PHRASES = {
            "enterprise", "fortune 500", "multinational", "large-scale", "global enterprise",
    };

    /** Classifies from already-normalized job text, never fabricating a signal beyond it. */
    public IndustryFit classify(String title, String description) {
        return classify(title, description, null);
    }

    public IndustryFit classify(String title, String description, String company) {
        String haystack = ((title == null ? "" : title) + " " + (description == null ? "" : description)
                + " " + (company == null ? "" : company)).toLowerCase();
        if (haystack.isBlank()) return IndustryFit.UNKNOWN;

        if (containsAny(haystack, BANKING_PHRASES)) return IndustryFit.BANKING;
        if (containsAny(haystack, FINTECH_PHRASES)) return IndustryFit.FINTECH;
        if (containsAny(haystack, CLOUD_PHRASES)) return IndustryFit.CLOUD;
        if (containsAny(haystack, PLATFORM_PHRASES)) return IndustryFit.PLATFORM;
        if (containsAny(haystack, ENTERPRISE_PHRASES)) return IndustryFit.ENTERPRISE;
        return IndustryFit.UNKNOWN;
    }

    private static boolean containsAny(String haystack, String[] phrases) {
        for (String p : phrases) {
            if (haystack.contains(p)) return true;
        }
        return false;
    }
}
