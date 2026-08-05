package ai.careerpilot.execution.verification.evidence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0 — the confidence ladder. The critical assertions are the negative ones: nothing short of
 * genuine multi-signal evidence may return a level that permits {@code STATUS_SUBMITTED}.
 */
class VerificationAdjudicatorTest {

    private final VerificationAdjudicator adjudicator = new VerificationAdjudicator();

    @Test
    void nullBundle_isNone_andDoesNotPermitSubmitted() {
        ConfidenceLevel level = adjudicator.adjudicate(null);
        assertThat(level).isEqualTo(ConfidenceLevel.NONE);
        assertThat(level.permitsSubmittedStatus()).isFalse();
    }

    @Test
    void emptyBundle_isNone() {
        assertThat(adjudicator.adjudicate(EvidenceBundle.empty())).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void applicationIdPlusSuccessDom_isConfirmed() {
        EvidenceBundle bundle = EvidenceBundle.of(
                VerificationSignal.applicationId("4XJ-88213"),
                VerificationSignal.successDom("application received"));
        ConfidenceLevel level = adjudicator.adjudicate(bundle);
        assertThat(level).isEqualTo(ConfidenceLevel.CONFIRMED);
        assertThat(level.permitsSubmittedStatus()).isTrue();
    }

    @Test
    void applicationIdAlone_isStrong_andPermitsSubmitted() {
        ConfidenceLevel level = adjudicator.adjudicate(
                EvidenceBundle.of(VerificationSignal.applicationId("A19822")));
        assertThat(level).isEqualTo(ConfidenceLevel.STRONG);
        assertThat(level.permitsSubmittedStatus()).isTrue();
    }

    @Test
    void twoStrongSignals_isStrong() {
        ConfidenceLevel level = adjudicator.adjudicate(EvidenceBundle.of(
                VerificationSignal.successDom("thank you for applying"),
                VerificationSignal.urlTransition("https://jobs.lever.co/acme/123/thanks")));
        assertThat(level).isEqualTo(ConfidenceLevel.STRONG);
        assertThat(level.permitsSubmittedStatus()).isTrue();
    }

    @Test
    void singleStrongSignal_isWeak_andDoesNotPermitSubmitted() {
        ConfidenceLevel level = adjudicator.adjudicate(
                EvidenceBundle.of(VerificationSignal.successDom("thank you for applying")));
        assertThat(level).isEqualTo(ConfidenceLevel.WEAK);
        assertThat(level.permitsSubmittedStatus()).isFalse();
    }

    @Test
    void screenshotAlone_isWeak_neverProof() {
        ConfidenceLevel level = adjudicator.adjudicate(
                EvidenceBundle.of(VerificationSignal.screenshot("execution-screenshots/x/after.png")));
        assertThat(level).isEqualTo(ConfidenceLevel.WEAK);
        assertThat(level.permitsSubmittedStatus()).isFalse();
    }

    @Test
    void errorState_overridesEveryPositiveSignal() {
        EvidenceBundle bundle = EvidenceBundle.of(
                VerificationSignal.applicationId("A19822"),
                VerificationSignal.successDom("application received"),
                VerificationSignal.errorState("something went wrong"));
        ConfidenceLevel level = adjudicator.adjudicate(bundle);
        assertThat(level).isEqualTo(ConfidenceLevel.NONE);
        assertThat(level.permitsSubmittedStatus()).isFalse();
    }

    @Test
    void explain_alwaysProducesReasoning() {
        EvidenceBundle bundle = EvidenceBundle.of(VerificationSignal.successDom("application received"));
        assertThat(adjudicator.explain(bundle, ConfidenceLevel.WEAK)).contains("unproven");
        assertThat(adjudicator.explain(EvidenceBundle.empty(), ConfidenceLevel.NONE)).contains("no verification signals");
    }

    @Test
    void onlyConfirmedAndStrongPermitSubmittedStatus() {
        assertThat(ConfidenceLevel.CONFIRMED.permitsSubmittedStatus()).isTrue();
        assertThat(ConfidenceLevel.STRONG.permitsSubmittedStatus()).isTrue();
        assertThat(ConfidenceLevel.WEAK.permitsSubmittedStatus()).isFalse();
        assertThat(ConfidenceLevel.NONE.permitsSubmittedStatus()).isFalse();
    }
}
