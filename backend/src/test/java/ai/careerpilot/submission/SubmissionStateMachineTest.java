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
    void waitingManualSubmissionOnlyAdvancesToUserReportedSubmittedOrFailed() {
        // Guided Apply — the one legal way out: an explicit user confirmation. No automatic
        // progression to TRACKING/SUBMITTED (CareerPilot never inferred a real submission).
        assertFalse(SubmissionStateMachine.isTerminal(STATUS_WAITING_MANUAL_SUBMISSION));
        assertTrue(SubmissionStateMachine.isKnown(STATUS_WAITING_MANUAL_SUBMISSION));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_WAITING_MANUAL_SUBMISSION, STATUS_TRACKING));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_WAITING_MANUAL_SUBMISSION, STATUS_SUBMITTED));
        assertTrue(SubmissionStateMachine.canTransition(STATUS_WAITING_MANUAL_SUBMISSION, STATUS_USER_REPORTED_SUBMITTED));
        assertTrue(SubmissionStateMachine.canTransition(STATUS_WAITING_MANUAL_SUBMISSION, STATUS_FAILED));
    }

    @Test
    void userReportedSubmittedIsTerminalWithNoOutgoingTransitions() {
        // Deliberately distinct from SUBMITTED/SUBMIT_UNVERIFIED: this is the candidate's own claim,
        // never verified by CareerPilot, and must never be relabelled as something stronger.
        assertTrue(SubmissionStateMachine.isTerminal(STATUS_USER_REPORTED_SUBMITTED));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_USER_REPORTED_SUBMITTED, STATUS_FAILED));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_USER_REPORTED_SUBMITTED, STATUS_TRACKING));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_USER_REPORTED_SUBMITTED, STATUS_COMPLETED));
    }

    /**
     * Final Hardening Pass — {@code resolvesManualCompletion} is what {@code ApplicationCardService}
     * uses to suppress a stale Guided Apply banner. Every status here must be reachable ONLY via a
     * genuine {@code SUBMITTING} attempt or the explicit user confirmation — never via a path that
     * still leaves the candidate needing to act.
     */
    @Test
    void resolvesManualCompletionCoversEveryPostSubmitAndUserConfirmedStatus() {
        for (String resolved : new String[] {
                STATUS_SUBMITTED, STATUS_SUBMIT_UNVERIFIED, STATUS_VERIFYING, STATUS_VERIFIED,
                STATUS_VERIFICATION_FAILED, STATUS_TRACKING, STATUS_COMPLETED, STATUS_USER_REPORTED_SUBMITTED}) {
            assertTrue(SubmissionStateMachine.resolvesManualCompletion(resolved),
                    resolved + " must resolve manual completion");
        }
    }

    @Test
    void resolvesManualCompletionIsFalseForEveryStillOpenOrFailedStatus() {
        for (String open : new String[] {
                STATUS_CREATED, STATUS_VALIDATING, STATUS_PACKAGE_READY, STATUS_REVIEW_READY,
                STATUS_COMPANY_READY, STATUS_STAR_READY, STATUS_READY_FOR_SUBMISSION, STATUS_WAITING_APPROVAL,
                STATUS_SUBMITTING, STATUS_WAITING_MANUAL_SUBMISSION, STATUS_FAILED}) {
            assertFalse(SubmissionStateMachine.resolvesManualCompletion(open),
                    open + " must NOT resolve manual completion — a failed pipeline does not mean "
                            + "the application need went away, and every other status here is still in flight");
        }
    }

    @Test
    void onlyWaitingManualSubmissionCanReachUserReportedSubmitted() {
        for (String active : new String[] {
                STATUS_CREATED, STATUS_VALIDATING, STATUS_PACKAGE_READY, STATUS_REVIEW_READY,
                STATUS_COMPANY_READY, STATUS_STAR_READY, STATUS_READY_FOR_SUBMISSION, STATUS_WAITING_APPROVAL,
                STATUS_SUBMITTING, STATUS_SUBMITTED, STATUS_VERIFYING, STATUS_VERIFIED,
                STATUS_VERIFICATION_FAILED, STATUS_TRACKING}) {
            assertFalse(SubmissionStateMachine.canTransition(active, STATUS_USER_REPORTED_SUBMITTED),
                    active + " -> USER_REPORTED_SUBMITTED must be illegal");
        }
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
                STATUS_VERIFICATION_FAILED, STATUS_TRACKING, STATUS_COMPLETED, STATUS_FAILED,
                STATUS_WAITING_MANUAL_SUBMISSION, STATUS_USER_REPORTED_SUBMITTED}) {
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
