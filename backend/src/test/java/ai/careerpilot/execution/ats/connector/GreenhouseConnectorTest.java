package ai.careerpilot.execution.ats.connector;

import ai.careerpilot.execution.browser.PlaywrightAutomationProvider;
import ai.careerpilot.execution.verification.VerificationStatus;
import ai.careerpilot.execution.verification.evidence.ConfirmationPageAnalyzer;
import ai.careerpilot.execution.verification.evidence.ConfirmationPageVerifier;
import ai.careerpilot.execution.verification.evidence.VerificationAdjudicator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Phase 0 (Browser Automation Platform) — verifySubmission now adjudicates real evidence.
 *
 * <p><b>Deliberate behaviour change from Phase 7.16.1.</b> The previous contract was "a captured
 * page longer than 50 characters is VERIFIED", and the previous version of this class asserted
 * exactly that ({@code "x".repeat(200)} ⇒ VERIFIED). That rule certified any rendered page —
 * including an error page — as a successful submission. Those assertions are inverted below,
 * intentionally: page length is no longer evidence of anything.
 */
class GreenhouseConnectorTest {

    private GreenhouseConnector connector() {
        return new GreenhouseConnector(mock(PlaywrightAutomationProvider.class),
                new ConfirmationPageVerifier(new ConfirmationPageAnalyzer(), new VerificationAdjudicator()));
    }

    @Test
    void nullConfirmationIsUnableToVerify() {
        assertEquals(VerificationStatus.UNABLE_TO_VERIFY, connector().verifySubmission(null).status());
    }

    @Test
    void blankConfirmationIsUnableToVerify() {
        assertEquals(VerificationStatus.UNABLE_TO_VERIFY, connector().verifySubmission("   ").status());
    }

    @Test
    void tooShortConfirmationIsUnableToVerify() {
        assertEquals(VerificationStatus.UNABLE_TO_VERIFY, connector().verifySubmission("thanks").status());
    }

    @Test
    void longButMeaninglessContentIsNoLongerVerified() {
        // Was asserted as VERIFIED before Phase 0 purely because it exceeds 50 characters.
        assertEquals(VerificationStatus.UNABLE_TO_VERIFY, connector().verifySubmission("x".repeat(200)).status());
    }

    @Test
    void longErrorPageIsNotVerified() {
        String page = "<div>Something went wrong. Please try submitting your application again.</div>";
        assertEquals(VerificationStatus.NOT_VERIFIED, connector().verifySubmission(page).status());
    }

    @Test
    void realConfirmationPageWithReferenceIsVerified() {
        String page = "<h1>Application received</h1><p>Confirmation number: 4XJ-88213</p>";
        var result = connector().verifySubmission(page);
        assertEquals(VerificationStatus.VERIFIED, result.status());
        assertTrue(result.method().startsWith("EVIDENCE_ADJUDICATION:"),
                "method should record the adjudicated confidence, was: " + result.method());
    }
}
