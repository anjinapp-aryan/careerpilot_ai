package ai.careerpilot.submission;

import org.junit.jupiter.api.Test;

import static ai.careerpilot.domain.ApplicationSubmissionSession.*;
import static org.junit.jupiter.api.Assertions.*;

class SubmissionStateMachineTest {

    @Test
    void createdAdvancesOnlyToValidating() {
        assertTrue(SubmissionStateMachine.canTransition(STATUS_CREATED, STATUS_VALIDATING));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_CREATED, STATUS_PACKAGE_READY));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_CREATED, STATUS_SUBMITTED));
    }

    @Test
    void validatingAdvancesOnlyToPackageReady() {
        assertTrue(SubmissionStateMachine.canTransition(STATUS_VALIDATING, STATUS_PACKAGE_READY));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_VALIDATING, STATUS_REVIEW_READY));
    }

    @Test
    void packageReadyAdvancesOnlyToReviewReady() {
        assertTrue(SubmissionStateMachine.canTransition(STATUS_PACKAGE_READY, STATUS_REVIEW_READY));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_PACKAGE_READY, STATUS_COMPANY_READY));
    }

    @Test
    void reviewReadyAdvancesOnlyToCompanyReady() {
        assertTrue(SubmissionStateMachine.canTransition(STATUS_REVIEW_READY, STATUS_COMPANY_READY));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_REVIEW_READY, STATUS_STAR_READY));
    }

    @Test
    void companyReadyAdvancesOnlyToStarReady() {
        assertTrue(SubmissionStateMachine.canTransition(STATUS_COMPANY_READY, STATUS_STAR_READY));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_COMPANY_READY, STATUS_READY_FOR_SUBMISSION));
    }

    @Test
    void starReadyAdvancesOnlyToReadyForSubmission() {
        assertTrue(SubmissionStateMachine.canTransition(STATUS_STAR_READY, STATUS_READY_FOR_SUBMISSION));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_STAR_READY, STATUS_WAITING_APPROVAL));
    }

    @Test
    void readyForSubmissionCanBranchToApprovalOrDirectlyToSubmitting() {
        assertTrue(SubmissionStateMachine.canTransition(STATUS_READY_FOR_SUBMISSION, STATUS_WAITING_APPROVAL));
        assertTrue(SubmissionStateMachine.canTransition(STATUS_READY_FOR_SUBMISSION, STATUS_SUBMITTING));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_READY_FOR_SUBMISSION, STATUS_SUBMITTED));
    }

    @Test
    void waitingApprovalAdvancesOnlyToSubmitting() {
        assertTrue(SubmissionStateMachine.canTransition(STATUS_WAITING_APPROVAL, STATUS_SUBMITTING));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_WAITING_APPROVAL, STATUS_SUBMITTED));
    }

    @Test
    void submittingCanBranchToSubmittedOrWaitingManualSubmission() {
        // Phase 7.16.5 — SUBMITTING only reaches SUBMITTED when a genuine automated submission
        // occurred; otherwise it routes to WAITING_MANUAL_SUBMISSION instead of fabricating SUBMITTED.
        assertTrue(SubmissionStateMachine.canTransition(STATUS_SUBMITTING, STATUS_SUBMITTED));
        assertTrue(SubmissionStateMachine.canTransition(STATUS_SUBMITTING, STATUS_WAITING_MANUAL_SUBMISSION));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_SUBMITTING, STATUS_VERIFIED));
    }

    @Test
    void waitingManualSubmissionHasNoForwardTransitionsButCanFail() {
        // A deliberate resting state (like WAITING_APPROVAL) — not currently auto-progressed
        // anywhere; only fail-closed FAILED is legal until a future manual-confirm action exists.
        assertFalse(SubmissionStateMachine.isTerminal(STATUS_WAITING_MANUAL_SUBMISSION));
        assertTrue(SubmissionStateMachine.isKnown(STATUS_WAITING_MANUAL_SUBMISSION));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_WAITING_MANUAL_SUBMISSION, STATUS_TRACKING));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_WAITING_MANUAL_SUBMISSION, STATUS_SUBMITTED));
        assertTrue(SubmissionStateMachine.canTransition(STATUS_WAITING_MANUAL_SUBMISSION, STATUS_FAILED));
    }

    @Test
    void submittedAdvancesOnlyToVerifying() {
        // Phase 7.16.1 — VERIFYING inserted so VERIFIED can never be a bare transition; it must
        // pass through a real evidence check first.
        assertTrue(SubmissionStateMachine.canTransition(STATUS_SUBMITTED, STATUS_VERIFYING));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_SUBMITTED, STATUS_VERIFIED));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_SUBMITTED, STATUS_TRACKING));
    }

    @Test
    void verifyingBranchesToVerifiedOrVerificationFailed() {
        assertTrue(SubmissionStateMachine.canTransition(STATUS_VERIFYING, STATUS_VERIFIED));
        assertTrue(SubmissionStateMachine.canTransition(STATUS_VERIFYING, STATUS_VERIFICATION_FAILED));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_VERIFYING, STATUS_TRACKING));
    }

    @Test
    void verifiedAdvancesOnlyToTracking() {
        assertTrue(SubmissionStateMachine.canTransition(STATUS_VERIFIED, STATUS_TRACKING));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_VERIFIED, STATUS_COMPLETED));
    }

    @Test
    void verificationFailedAdvancesOnlyToTracking() {
        // Deliberately NOT terminal and NOT the same as FAILED — the application likely was
        // submitted, we simply couldn't prove it, so tracking still proceeds.
        assertTrue(SubmissionStateMachine.canTransition(STATUS_VERIFICATION_FAILED, STATUS_TRACKING));
        assertFalse(SubmissionStateMachine.isTerminal(STATUS_VERIFICATION_FAILED));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_VERIFICATION_FAILED, STATUS_COMPLETED));
    }

    @Test
    void trackingAdvancesOnlyToCompleted() {
        assertTrue(SubmissionStateMachine.canTransition(STATUS_TRACKING, STATUS_COMPLETED));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_TRACKING, STATUS_FAILED + "X"));
    }

    @Test
    void failedReachableFromEveryActiveStatus() {
        for (String active : new String[] {
                STATUS_CREATED, STATUS_VALIDATING, STATUS_PACKAGE_READY, STATUS_REVIEW_READY,
                STATUS_COMPANY_READY, STATUS_STAR_READY, STATUS_READY_FOR_SUBMISSION, STATUS_WAITING_APPROVAL,
                STATUS_SUBMITTING, STATUS_SUBMITTED, STATUS_WAITING_MANUAL_SUBMISSION, STATUS_VERIFYING,
                STATUS_VERIFIED, STATUS_VERIFICATION_FAILED, STATUS_TRACKING}) {
            assertTrue(SubmissionStateMachine.canTransition(active, STATUS_FAILED), active + " -> FAILED must be legal");
        }
    }

    @Test
    void completedIsTerminalWithNoOutgoingTransitions() {
        assertTrue(SubmissionStateMachine.isTerminal(STATUS_COMPLETED));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_COMPLETED, STATUS_FAILED));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_COMPLETED, STATUS_TRACKING));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_COMPLETED, STATUS_CREATED));
    }

    @Test
    void failedIsTerminalWithNoOutgoingTransitions() {
        assertTrue(SubmissionStateMachine.isTerminal(STATUS_FAILED));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_FAILED, STATUS_COMPLETED));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_FAILED, STATUS_CREATED));
    }

    @Test
    void sameStatusTransitionIsIllegal() {
        assertFalse(SubmissionStateMachine.canTransition(STATUS_CREATED, STATUS_CREATED));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_COMPLETED, STATUS_COMPLETED));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_FAILED, STATUS_FAILED));
    }

    @Test
    void nullArgumentsAreIllegal() {
        assertFalse(SubmissionStateMachine.canTransition(null, STATUS_VALIDATING));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_CREATED, null));
        assertFalse(SubmissionStateMachine.canTransition(null, null));
    }

    @Test
    void unknownStatusStringsAreIllegal() {
        assertFalse(SubmissionStateMachine.canTransition("NOT_A_STATUS", STATUS_VALIDATING));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_CREATED, "NOT_A_STATUS"));
        assertFalse(SubmissionStateMachine.canTransition("NOT_A_STATUS", "ALSO_NOT_A_STATUS"));
    }

    @Test
    void illegalJumpsAreRejected() {
        assertFalse(SubmissionStateMachine.canTransition(STATUS_CREATED, STATUS_SUBMITTED));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_VALIDATING, STATUS_COMPLETED));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_PACKAGE_READY, STATUS_WAITING_APPROVAL));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_SUBMITTED, STATUS_COMPLETED));
    }

    @Test
    void isKnownRecognizesAllStatuses() {
        for (String s : new String[] {
                STATUS_CREATED, STATUS_VALIDATING, STATUS_PACKAGE_READY, STATUS_REVIEW_READY,
                STATUS_COMPANY_READY, STATUS_STAR_READY, STATUS_READY_FOR_SUBMISSION, STATUS_WAITING_APPROVAL,
                STATUS_SUBMITTING, STATUS_SUBMITTED, STATUS_VERIFYING, STATUS_VERIFIED,
                STATUS_VERIFICATION_FAILED, STATUS_TRACKING, STATUS_COMPLETED, STATUS_FAILED}) {
            assertTrue(SubmissionStateMachine.isKnown(s), s + " should be known");
        }
        assertFalse(SubmissionStateMachine.isKnown("BOGUS"));
    }

    @Test
    void backwardTransitionIsIllegal() {
        assertFalse(SubmissionStateMachine.canTransition(STATUS_PACKAGE_READY, STATUS_VALIDATING));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_COMPLETED, STATUS_TRACKING));
    }
}
