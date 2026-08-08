package ai.careerpilot.execution.browser.validation;

import ai.careerpilot.execution.browser.validation.AutomationBlocker.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 — a blocker makes automation impossible, and no score may override that.
 *
 * <p>The measured defect this pins, from three separate live GitLab postings during the F5 audit:
 * <pre>captchaDetected = true    confidence = 94    band = HIGH    ready = true</pre>
 * A page guarded by reCAPTCHA was certified executable because {@code AutomationConfidence} had no
 * awareness of CAPTCHA at all.
 */
class AutomationBlockerTest {

    /** A page whose fields are all perfectly mapped — the exact shape that used to score 94/HIGH. */
    private static SelectorCoverage perfectCoverage() {
        return new SelectorCoverage(22, 22, 22, 0, 0, 8, 8, 0, java.util.Map.of());
    }

    @ParameterizedTest(name = "{0} forces ready=false however good the coverage is")
    @EnumSource(Reason.class)
    void anyBlockerMakesAPerfectPageUnready(Reason reason) {
        AutomationConfidence confidence =
                AutomationConfidence.from(perfectCoverage(), List.of(AutomationBlocker.of(reason)));

        assertThat(confidence.ready()).isFalse();
        assertThat(confidence.blocked()).isTrue();
        assertThat(confidence.blockers()).hasSize(1);
    }

    @Test
    @DisplayName("the exact measured defect: CAPTCHA + 94% no longer yields ready=true")
    void captchaCannotBeReady() {
        AutomationConfidence before = AutomationConfidence.from(perfectCoverage());
        // Confirm the fixture really is the high-scoring case, or the test proves nothing.
        assertThat(before.score()).isGreaterThanOrEqualTo(85);
        assertThat(before.band()).isEqualTo(AutomationConfidence.Band.HIGH);
        assertThat(before.ready()).isTrue();

        AutomationConfidence after = AutomationConfidence.from(perfectCoverage(),
                List.of(AutomationBlocker.of(Reason.CAPTCHA)));

        assertThat(after.score()).isEqualTo(before.score());   // analysis quality is unchanged
        assertThat(after.ready()).isFalse();                   // ...but execution is impossible
        assertThat(after.blocked()).isTrue();
    }

    @Test
    @DisplayName("ready=true cannot be constructed alongside a blocker, even directly")
    void theInvariantHoldsEvenIfACallerPassesReadyTrue() {
        // Defence in depth: the compact constructor enforces this, so a future call site that
        // computes `ready` itself and forgets blockers still cannot produce a false READY.
        AutomationConfidence forced = new AutomationConfidence(99, AutomationConfidence.Band.HIGH,
                true, "hand-built", List.of(AutomationBlocker.of(Reason.CAPTCHA)));

        assertThat(forced.ready()).isFalse();
    }

    @Test
    void theRationaleLeadsWithTheBlockNotTheScore() {
        AutomationConfidence c = AutomationConfidence.from(perfectCoverage(),
                List.of(AutomationBlocker.of(Reason.CAPTCHA)));

        assertThat(c.rationale()).startsWith("AUTOMATION BLOCKED");
        assertThat(c.rationale()).contains("CAPTCHA");
        assertThat(c.rationale()).contains("ANALYSED");
    }

    @Test
    void theSnapshotStatesBlockedAlongsideNotReady() {
        var snap = AutomationConfidence.from(perfectCoverage(),
                List.of(AutomationBlocker.of(Reason.CAPTCHA))).snapshot();

        assertThat(snap).containsEntry("blocked", true)
                .containsEntry("ready", false)
                .containsEntry("blockedReason", "CAPTCHA");
    }

    @Test
    void noBlockersLeavesTheOriginalBehaviourExactlyAsItWas() {
        AutomationConfidence c = AutomationConfidence.from(perfectCoverage(), List.of());

        assertThat(c.blocked()).isFalse();
        assertThat(c.ready()).isTrue();
        assertThat(c.blockers()).isEmpty();
        assertThat(c.snapshot()).containsEntry("blockedReason", null);
    }

    @Test
    void anEmptyPageIsBlockedAsNoFormRatherThanMerelyScoringZero() {
        AutomationConfidence c = AutomationConfidence.from(SelectorCoverage.empty());

        assertThat(c.blocked()).isTrue();
        assertThat(c.blockers().get(0).reason()).isEqualTo(Reason.NO_FORM);
        assertThat(c.ready()).isFalse();
    }

    @Test
    void multipleBlockersAreAllReportedAndTheFirstIsTheHeadline() {
        AutomationConfidence c = AutomationConfidence.from(perfectCoverage(), List.of(
                AutomationBlocker.of(Reason.CAPTCHA),
                AutomationBlocker.of(Reason.MISSING_REQUIRED_DATA)));

        assertThat(c.blockers()).hasSize(2);
        assertThat(c.snapshot()).containsEntry("blockedReason", "CAPTCHA");
    }
}
