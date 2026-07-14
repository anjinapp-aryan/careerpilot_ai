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
    void submittingAdvancesOnlyToSubmitted() {
        assertTrue(SubmissionStateMachine.canTransition(STATUS_SUBMITTING, STATUS_SUBMITTED));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_SUBMITTING, STATUS_VERIFIED));
    }

    @Test
    void submittedAdvancesOnlyToVerified() {
        assertTrue(SubmissionStateMachine.canTransition(STATUS_SUBMITTED, STATUS_VERIFIED));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_SUBMITTED, STATUS_TRACKING));
    }

    @Test
    void verifiedAdvancesOnlyToTracking() {
        assertTrue(SubmissionStateMachine.canTransition(STATUS_VERIFIED, STATUS_TRACKING));
        assertFalse(SubmissionStateMachine.canTransition(STATUS_VERIFIED, STATUS_COMPLETED));
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
                STATUS_SUBMITTING, STATUS_SUBMITTED, STATUS_VERIFIED, STATUS_TRACKING}) {
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
                STATUS_SUBMITTING, STATUS_SUBMITTED, STATUS_VERIFIED, STATUS_TRACKING, STATUS_COMPLETED, STATUS_FAILED}) {
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
