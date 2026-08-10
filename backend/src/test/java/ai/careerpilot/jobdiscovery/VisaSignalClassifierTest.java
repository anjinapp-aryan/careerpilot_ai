package ai.careerpilot.jobdiscovery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Global Job Discovery Expansion — {@link VisaSignalClassifier}. The load-bearing rule under
 * test is that UNKNOWN never upgrades to CONFIRMED or MENTIONED: absence of evidence classifies
 * as UNKNOWN, never inferred from anything else about the posting.
 */
class VisaSignalClassifierTest {

    private final VisaSignalClassifier classifier = new VisaSignalClassifier();

    @Test
    void explicitCommitmentClassifiesAsConfirmed() {
        assertThat(classifier.classify("Senior Engineer", "We sponsor H-1B visas for the right candidate."))
                .isEqualTo(SponsorshipSignal.CONFIRMED);
        assertThat(classifier.classify("Senior Engineer", "Visa sponsorship available for this role."))
                .isEqualTo(SponsorshipSignal.CONFIRMED);
    }

    @Test
    void bareMentionClassifiesAsMentionedNotConfirmed() {
        assertThat(classifier.classify("Senior Engineer", "Some visa support may be provided."))
                .isEqualTo(SponsorshipSignal.MENTIONED);
        assertThat(classifier.classify("Senior Engineer", "Candidates must have existing work permit."))
                .isEqualTo(SponsorshipSignal.MENTIONED);
    }

    @Test
    void explicitDenialClassifiesAsNotSupported() {
        assertThat(classifier.classify("Senior Engineer", "We cannot sponsor visas for this position."))
                .isEqualTo(SponsorshipSignal.NOT_SUPPORTED);
        assertThat(classifier.classify("Senior Engineer", "No visa sponsorship is available."))
                .isEqualTo(SponsorshipSignal.NOT_SUPPORTED);
    }

    @Test
    void noEvidenceClassifiesAsUnknownNeverConfirmed() {
        assertThat(classifier.classify("Senior Java Engineer", "Build distributed systems with Spring Boot and AWS."))
                .isEqualTo(SponsorshipSignal.UNKNOWN);
    }

    @Test
    void unknownIsNeverUpgradedByCountryCompanyOrSeniorityContext() {
        // Same neutral description regardless of how "sponsorship-friendly" the surrounding
        // context might seem (multinational enterprise, senior role, Germany) — the classifier
        // has no country/company/seniority input at all, so it structurally cannot infer from them.
        String neutralText = "Principal Software Architect at a Fortune 500 multinational enterprise in Berlin, Germany.";
        assertThat(classifier.classify("Principal Software Architect", neutralText))
                .isEqualTo(SponsorshipSignal.UNKNOWN);
    }

    @Test
    void blankTextClassifiesAsUnknown() {
        assertThat(classifier.classify(null, null)).isEqualTo(SponsorshipSignal.UNKNOWN);
        assertThat(classifier.classify("", "")).isEqualTo(SponsorshipSignal.UNKNOWN);
    }

    @Test
    void denialTakesPrecedenceOverAnyPositiveSignalInTheSameText() {
        // A posting that both mentions sponsorship AND explicitly denies it (e.g. templated
        // boilerplate followed by a real denial) must resolve to the honest negative, not a
        // false positive from an earlier positive keyword.
        assertThat(classifier.classify("Senior Engineer",
                "We previously offered visa sponsorship but currently cannot sponsor visas."))
                .isEqualTo(SponsorshipSignal.NOT_SUPPORTED);
    }
}
