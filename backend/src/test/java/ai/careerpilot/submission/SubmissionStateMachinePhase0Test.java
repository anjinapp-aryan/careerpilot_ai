package ai.careerpilot.submission;

import ai.careerpilot.domain.ApplicationSubmissionSession;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0 (Browser Automation Platform) — the {@code SUBMIT_UNVERIFIED} edges, and the guarantee
 * that adding them did not open a path to labelling an unverified submission as SUBMITTED.
 */
class SubmissionStateMachinePhase0Test {

    @Test
    void submittingCanReachSubmitUnverified() {
        assertThat(SubmissionStateMachine.canTransition(
                ApplicationSubmissionSession.STATUS_SUBMITTING,
                ApplicationSubmissionSession.STATUS_SUBMIT_UNVERIFIED)).isTrue();
    }

    @Test
    void submitUnverifiedProceedsToTracking() {
        assertThat(SubmissionStateMachine.canTransition(
                ApplicationSubmissionSession.STATUS_SUBMIT_UNVERIFIED,
                ApplicationSubmissionSession.STATUS_TRACKING)).isTrue();
    }

    @Test
    void submitUnverifiedCanNeverBeRelabelledSubmitted() {
        assertThat(SubmissionStateMachine.canTransition(
                ApplicationSubmissionSession.STATUS_SUBMIT_UNVERIFIED,
                ApplicationSubmissionSession.STATUS_SUBMITTED)).isFalse();
        assertThat(SubmissionStateMachine.canTransition(
                ApplicationSubmissionSession.STATUS_SUBMIT_UNVERIFIED,
                ApplicationSubmissionSession.STATUS_VERIFIED)).isFalse();
    }

    @Test
    void submitUnverifiedCanStillFailClosed() {
        assertThat(SubmissionStateMachine.canTransition(
                ApplicationSubmissionSession.STATUS_SUBMIT_UNVERIFIED,
                ApplicationSubmissionSession.STATUS_FAILED)).isTrue();
    }

    @Test
    void submitUnverifiedIsAKnownNonTerminalStatus() {
        assertThat(SubmissionStateMachine.isKnown(ApplicationSubmissionSession.STATUS_SUBMIT_UNVERIFIED)).isTrue();
        assertThat(SubmissionStateMachine.isTerminal(ApplicationSubmissionSession.STATUS_SUBMIT_UNVERIFIED)).isFalse();
    }

    @Test
    void preExistingSubmittingEdgesAreUnchanged() {
        assertThat(SubmissionStateMachine.canTransition(
                ApplicationSubmissionSession.STATUS_SUBMITTING,
                ApplicationSubmissionSession.STATUS_SUBMITTED)).isTrue();
        assertThat(SubmissionStateMachine.canTransition(
                ApplicationSubmissionSession.STATUS_SUBMITTING,
                ApplicationSubmissionSession.STATUS_WAITING_MANUAL_SUBMISSION)).isTrue();
    }

    @Test
    void waitingManualSubmissionRemainsADeadEnd() {
        // Still a known defect, deliberately out of Phase 0's scope — pinned so a later phase
        // that closes it does so intentionally rather than by accident.
        assertThat(SubmissionStateMachine.canTransition(
                ApplicationSubmissionSession.STATUS_WAITING_MANUAL_SUBMISSION,
                ApplicationSubmissionSession.STATUS_SUBMITTING)).isFalse();
    }
}
