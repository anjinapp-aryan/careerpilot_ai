package ai.careerpilot.execution.verification.evidence;

import ai.careerpilot.execution.verification.VerificationResult;
import ai.careerpilot.execution.verification.VerificationStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0 — the analyzer plus the verifier, exercised together against realistic captured pages.
 *
 * <p>The regression this locks down is explicit: the previous rule returned VERIFIED for any
 * captured page over 50 characters, so a rendered error page certified a failed submission as a
 * success. {@link #longErrorPage_isNotVerified_previouslyWouldHaveBeenVerified()} is that exact
 * case.
 */
class ConfirmationPageVerifierTest {

    private final ConfirmationPageVerifier verifier =
            new ConfirmationPageVerifier(new ConfirmationPageAnalyzer(), new VerificationAdjudicator());

    @Test
    void nullCapture_isUnableToVerify() {
        VerificationResult result = verifier.verify(null);
        assertThat(result.status()).isEqualTo(VerificationStatus.UNABLE_TO_VERIFY);
        assertThat(result.method()).contains(ConfidenceLevel.NONE.name());
    }

    @Test
    void blankCapture_isUnableToVerify() {
        assertThat(verifier.verify("   ").status()).isEqualTo(VerificationStatus.UNABLE_TO_VERIFY);
    }

    @Test
    void longErrorPage_isNotVerified_previouslyWouldHaveBeenVerified() {
        String page = "<html><body><div class='alert'>Something went wrong. "
                + "Please try submitting your application again in a few minutes.</div></body></html>";
        assertThat(page.length()).isGreaterThan(50); // the old rule's entire test
        VerificationResult result = verifier.verify(page);
        assertThat(result.status()).isEqualTo(VerificationStatus.NOT_VERIFIED);
        assertThat(result.method()).contains(ConfidenceLevel.NONE.name());
    }

    @Test
    void longIrrelevantPage_isUnableToVerify_notVerified() {
        String page = "<html><body><nav>Careers</nav><p>We are hiring across engineering, design "
                + "and operations. Browse our open roles below.</p></body></html>";
        assertThat(page.length()).isGreaterThan(50);
        assertThat(verifier.verify(page).status()).isEqualTo(VerificationStatus.UNABLE_TO_VERIFY);
    }

    @Test
    void confirmationPhraseOnly_isWeak_soNotVerified() {
        String page = "<html><body><h1>Thank you for applying!</h1></body></html>";
        VerificationResult result = verifier.verify(page);
        assertThat(result.status()).isEqualTo(VerificationStatus.UNABLE_TO_VERIFY);
        assertThat(result.method()).contains(ConfidenceLevel.WEAK.name());
    }

    @Test
    void confirmationPhrasePlusReference_isVerified() {
        String page = "<html><body><h1>Application received</h1>"
                + "<p>Confirmation number: 4XJ-88213</p></body></html>";
        VerificationResult result = verifier.verify(page);
        assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(result.method()).contains(ConfidenceLevel.CONFIRMED.name());
    }

    @Test
    void referenceAlone_isVerified_atStrong() {
        String page = "<html><body><p>Application ID: A19822-KKQ</p></body></html>";
        VerificationResult result = verifier.verify(page);
        assertThat(result.status()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(result.method()).contains(ConfidenceLevel.STRONG.name());
    }

    @Test
    void phraseSplitAcrossMarkup_stillMatches() {
        String page = "<h1>Thank you <strong>for applying</strong></h1><p>Reference: ZZ-4410</p>";
        assertThat(verifier.verify(page).status()).isEqualTo(VerificationStatus.VERIFIED);
    }

    @Test
    void errorPageWithConfirmationTemplate_failsClosed() {
        // A page that renders both a confirmation template and a validation error must not verify.
        String page = "<h1>Thank you for applying</h1><div>There was a problem with your submission.</div>"
                + "<p>Confirmation number: 4XJ-88213</p>";
        assertThat(verifier.verify(page).status()).isEqualTo(VerificationStatus.NOT_VERIFIED);
    }

    @Test
    void analyzer_extractsNoReferenceFromBareToken() {
        // An unlabelled alphanumeric token is not a confirmation reference.
        EvidenceBundle bundle = new ConfirmationPageAnalyzer().analyze("<p>ABC12345</p>");
        assertThat(bundle.has(SignalType.APPLICATION_ID)).isFalse();
    }

    @Test
    void evidenceBundle_isImmutable() {
        EvidenceBundle one = EvidenceBundle.of(VerificationSignal.successDom("application received"));
        EvidenceBundle two = one.with(VerificationSignal.applicationId("X-1"));
        assertThat(one.signals()).hasSize(1);
        assertThat(two.signals()).hasSize(2);
    }
}
