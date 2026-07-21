package ai.careerpilot.execution.ats.connector;

import ai.careerpilot.execution.browser.PlaywrightAutomationProvider;
import ai.careerpilot.execution.verification.VerificationStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Phase 7.16.1 — verifySubmission must never claim VERIFIED without a substantial captured page;
 * a blank or trivially-short capture is honestly UNABLE_TO_VERIFY, never upgraded.
 */
class GreenhouseConnectorTest {

    private GreenhouseConnector connector() {
        return new GreenhouseConnector(mock(PlaywrightAutomationProvider.class));
    }

    @Test
    void nullConfirmationIsUnableToVerify() {
        var result = connector().verifySubmission(null);
        assertEquals(VerificationStatus.UNABLE_TO_VERIFY, result.status());
    }

    @Test
    void blankConfirmationIsUnableToVerify() {
        var result = connector().verifySubmission("   ");
        assertEquals(VerificationStatus.UNABLE_TO_VERIFY, result.status());
    }

    @Test
    void tooShortConfirmationIsUnableToVerify() {
        var result = connector().verifySubmission("thanks");
        assertEquals(VerificationStatus.UNABLE_TO_VERIFY, result.status());
    }

    @Test
    void substantialCapturedContentIsVerified() {
        String captured = "x".repeat(200);
        var result = connector().verifySubmission(captured);
        assertEquals(VerificationStatus.VERIFIED, result.status());
        assertEquals("POST_SUBMIT_PAGE_CAPTURE", result.method());
    }
}
