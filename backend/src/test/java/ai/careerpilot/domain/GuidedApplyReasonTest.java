package ai.careerpilot.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GuidedApplyReasonTest {

    @Test
    void captchaOrLoginWallClassifiesAsCaptcha() {
        assertThat(GuidedApplyReason.fromFailureReason(
                "captcha or login wall detected — routed to human review")).isEqualTo(GuidedApplyReason.CAPTCHA);
        assertThat(GuidedApplyReason.fromFailureReason(
                "captcha or login wall detected on resubmit — routed to human review"))
                .isEqualTo(GuidedApplyReason.CAPTCHA);
    }

    @Test
    void ineligibleConnectorClassifiesAsAutomationBlocked() {
        assertThat(GuidedApplyReason.fromFailureReason("connector 'workday' is not guest-apply eligible"))
                .isEqualTo(GuidedApplyReason.AUTOMATION_BLOCKED);
    }

    @Test
    void unresolvedRequiredFieldsClassifyAsManualRequired() {
        assertThat(GuidedApplyReason.fromFailureReason(
                "required fields could not be filled from verified data: [Resume: ...]"))
                .isEqualTo(GuidedApplyReason.MANUAL_REQUIRED);
    }

    @Test
    void approvalEnqueueFailureClassifiesAsUnknownBlocker() {
        assertThat(GuidedApplyReason.fromFailureReason("form-screenshot approval enqueue failed (approval disabled?)"))
                .isEqualTo(GuidedApplyReason.UNKNOWN_BLOCKER);
    }

    @Test
    void nullOrBlankReasonIsUnknownBlockerNeverGuessed() {
        assertThat(GuidedApplyReason.fromFailureReason(null)).isEqualTo(GuidedApplyReason.UNKNOWN_BLOCKER);
        assertThat(GuidedApplyReason.fromFailureReason("")).isEqualTo(GuidedApplyReason.UNKNOWN_BLOCKER);
        assertThat(GuidedApplyReason.fromFailureReason("   ")).isEqualTo(GuidedApplyReason.UNKNOWN_BLOCKER);
    }

    @Test
    void unrecognisedButPresentReasonIsHonestlyManualRequired() {
        // Never invents a specific cause it can't identify — MANUAL_REQUIRED is the honest catch-all,
        // distinct from UNKNOWN_BLOCKER (reserved for "no reason recorded at all").
        assertThat(GuidedApplyReason.fromFailureReason("no execution backend configured (browser + ATS connectors disabled)"))
                .isEqualTo(GuidedApplyReason.MANUAL_REQUIRED);
        assertThat(GuidedApplyReason.fromFailureReason("application package not ASSEMBLED (status=DRAFT)"))
                .isEqualTo(GuidedApplyReason.MANUAL_REQUIRED);
    }

    @Test
    void caseInsensitive() {
        assertThat(GuidedApplyReason.fromFailureReason("CAPTCHA OR LOGIN WALL DETECTED"))
                .isEqualTo(GuidedApplyReason.CAPTCHA);
    }
}
