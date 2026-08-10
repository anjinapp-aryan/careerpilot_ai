package ai.careerpilot.jobdiscovery;

import org.springframework.stereotype.Component;

/**
 * Global Job Discovery Expansion — deterministic, keyword-based classification into
 * {@link SponsorshipSignal}'s 4 states. Same discipline as {@link JobEnricher#detectSponsorship}
 * (pure, no I/O, no LLM call per job) but distinguishes a firm commitment (CONFIRMED) from a bare
 * mention (MENTIONED) instead of collapsing both into one boolean.
 *
 * <p>Deliberately does not replace {@link JobEnricher#detectSponsorship} — that method's
 * TRUE/FALSE/null result keeps driving every existing caller ({@code sponsorship_available}
 * filters, badges, {@code InternationalJobScoring}'s legacy fallback) unchanged. This classifier
 * only adds the richer {@code sponsorship_status} column.
 */
@Component
public class VisaSignalClassifier {

    private static final String[] NOT_SUPPORTED_PHRASES = {
            "no visa sponsorship", "cannot sponsor", "not able to sponsor", "no sponsorship",
            "unable to sponsor", "does not sponsor", "will not sponsor",
    };

    /** Firm, explicit commitments — never inferred from country/company/seniority. */
    private static final String[] CONFIRMED_PHRASES = {
            "we sponsor", "sponsorship available", "visa sponsorship available",
            "h1b sponsorship", "h-1b sponsorship", "sponsors work visas", "provides visa sponsorship",
            "will sponsor", "company sponsors", "sponsors h1b", "sponsors h-1b",
    };

    /** Raises the topic without establishing certainty. */
    private static final String[] MENTIONED_PHRASES = {
            "visa support", "work permit", "visa sponsorship", "sponsorship", "h1b", "h-1b",
            "open to sponsorship", "may sponsor", "visa assistance", "work authorization support",
    };

    /** Classifies from already-normalized job text (title + description), never fabricating a signal. */
    public SponsorshipSignal classify(String title, String description) {
        String haystack = ((title == null ? "" : title) + " " + (description == null ? "" : description))
                .toLowerCase();
        if (haystack.isBlank()) return SponsorshipSignal.UNKNOWN;

        for (String phrase : NOT_SUPPORTED_PHRASES) {
            if (haystack.contains(phrase)) return SponsorshipSignal.NOT_SUPPORTED;
        }
        for (String phrase : CONFIRMED_PHRASES) {
            if (haystack.contains(phrase)) return SponsorshipSignal.CONFIRMED;
        }
        for (String phrase : MENTIONED_PHRASES) {
            if (haystack.contains(phrase)) return SponsorshipSignal.MENTIONED;
        }
        return SponsorshipSignal.UNKNOWN;
    }
}
