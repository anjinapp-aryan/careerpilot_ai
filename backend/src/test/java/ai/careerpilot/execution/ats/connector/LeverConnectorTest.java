package ai.careerpilot.execution.ats.connector;

import ai.careerpilot.execution.browser.PlaywrightAutomationProvider;
import ai.careerpilot.execution.verification.VerificationStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Phase 7.16.1 — same honesty contract as {@code GreenhouseConnectorTest}. */
class LeverConnectorTest {

    private LeverConnector connector() {
        return new LeverConnector(mock(PlaywrightAutomationProvider.class));
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
    void substantialCapturedContentIsVerified() {
        assertEquals(VerificationStatus.VERIFIED, connector().verifySubmission("y".repeat(200)).status());
    }
}
