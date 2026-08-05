package ai.careerpilot.execution.ats.connector;

import ai.careerpilot.execution.browser.PlaywrightAutomationProvider;
import ai.careerpilot.execution.verification.VerificationStatus;
import ai.careerpilot.execution.verification.evidence.ConfirmationPageAnalyzer;
import ai.careerpilot.execution.verification.evidence.ConfirmationPageVerifier;
import ai.careerpilot.execution.verification.evidence.VerificationAdjudicator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Phase 0 — same corrected evidence contract as {@code GreenhouseConnectorTest}; see its javadoc. */
class LeverConnectorTest {

    private LeverConnector connector() {
        return new LeverConnector(mock(PlaywrightAutomationProvider.class),
                new ConfirmationPageVerifier(new ConfirmationPageAnalyzer(), new VerificationAdjudicator()));
    }

    @Test
    void nullConfirmationIsUnableToVerify() {
        assertEquals(VerificationStatus.UNABLE_TO_VERIFY, connector().verifySubmission(null).status());
    }

    @Test
    void tooShortConfirmationIsUnableToVerify() {
        assertEquals(VerificationStatus.UNABLE_TO_VERIFY, connector().verifySubmission("ok").status());
    }

    @Test
    void longButMeaninglessContentIsNoLongerVerified() {
        assertEquals(VerificationStatus.UNABLE_TO_VERIFY, connector().verifySubmission("y".repeat(200)).status());
    }

    @Test
    void realConfirmationPageWithReferenceIsVerified() {
        String page = "<h1>Thank you for applying</h1><p>Reference: LV-99120</p>";
        assertEquals(VerificationStatus.VERIFIED, connector().verifySubmission(page).status());
    }
}
