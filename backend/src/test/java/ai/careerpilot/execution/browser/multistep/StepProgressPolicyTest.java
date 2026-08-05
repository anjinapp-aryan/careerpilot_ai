package ai.careerpilot.execution.browser.multistep;

import ai.careerpilot.domain.ExecutionStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase F1 — the approval guarantee, proven as a property of persisted state.
 *
 * <p>The rule under test is the one the whole phase exists for: <b>no page may be filled after an
 * approval until the next approval has occurred.</b> Expressing it over records rather than a live
 * browser is what makes it provable — every wizard shape below is a list of rows, and a regression
 * would fail in milliseconds rather than during a real employer submission.
 */
class StepProgressPolicyTest {

    private static final int MAX_STEPS = 10;
    private static final UUID EXEC = UUID.randomUUID();

    private static ExecutionStep step(int number, String status, boolean finalStep) {
        return ExecutionStep.builder()
                .id(UUID.randomUUID()).executionId(EXEC).userId(UUID.randomUUID())
                .stepNumber(number).status(status).finalStep(finalStep).attemptCount(1)
                .build();
    }

    private static StepProgressPolicy.Decision decide(ExecutionStep... steps) {
        return StepProgressPolicy.decide(List.of(steps), MAX_STEPS);
    }

    @Nested
    @DisplayName("the approval guarantee")
    class ApprovalGuarantee {

        @Test
        @DisplayName("a page awaiting approval halts everything — nothing may be filled ahead")
        void awaitingApprovalHaltsEverything() {
            StepProgressPolicy.Decision d = decide(
                    step(1, ExecutionStep.STATUS_APPROVED, false),
                    step(2, ExecutionStep.STATUS_PENDING_APPROVAL, false));

            assertThat(d.action()).isEqualTo(StepProgressPolicy.Action.WAIT_FOR_APPROVAL);
            assertThat(d.stepNumber()).isEqualTo(2);
            assertThat(d.reason()).contains("no page may be filled until it is decided");
        }

        @Test
        @DisplayName("approving one page authorises exactly one more page, never the rest")
        void oneApprovalAuthorisesExactlyOnePage() {
            // The precise failure this phase exists to prevent: approve page 1, and pages 2,3,4
            // are filled unseen. Only page 2 may follow.
            StepProgressPolicy.Decision d = decide(step(1, ExecutionStep.STATUS_APPROVED, false));

            assertThat(d.action()).isEqualTo(StepProgressPolicy.Action.FILL_STEP);
            assertThat(d.stepNumber()).isEqualTo(2);
        }

        @Test
        @DisplayName("every page in a three-page wizard requires its own approval")
        void everyPageNeedsItsOwnApproval() {
            // 1 approved -> fill 2
            assertThat(decide(step(1, ExecutionStep.STATUS_APPROVED, false)).stepNumber()).isEqualTo(2);
            // 2 pending -> wait, NOT fill 3
            assertThat(decide(step(1, ExecutionStep.STATUS_APPROVED, false),
                    step(2, ExecutionStep.STATUS_PENDING_APPROVAL, false)).action())
                    .isEqualTo(StepProgressPolicy.Action.WAIT_FOR_APPROVAL);
            // 1,2 approved -> fill 3
            assertThat(decide(step(1, ExecutionStep.STATUS_APPROVED, false),
                    step(2, ExecutionStep.STATUS_APPROVED, false)).stepNumber()).isEqualTo(3);
        }

        @Test
        @DisplayName("a missing page in the sequence stops the run rather than being skipped")
        void noPageMayBeSkipped() {
            // Step 2 was never recorded: something filled a page nobody reviewed.
            StepProgressPolicy.Decision d = decide(
                    step(1, ExecutionStep.STATUS_APPROVED, false),
                    step(3, ExecutionStep.STATUS_APPROVED, false));

            assertThat(d.action()).isEqualTo(StepProgressPolicy.Action.STOP);
            assertThat(d.reason()).contains("missing from the record");
        }
    }

    @Nested
    @DisplayName("wizard shapes")
    class WizardShapes {

        @Test
        @DisplayName("single-page form: approved and terminal means ready to submit")
        void singlePageForm() {
            StepProgressPolicy.Decision d = decide(step(1, ExecutionStep.STATUS_APPROVED, true));

            assertThat(d.action()).isEqualTo(StepProgressPolicy.Action.READY_TO_SUBMIT);
            assertThat(d.stepNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("two-page wizard reaches submit only after both approvals")
        void twoPageWizard() {
            assertThat(decide(step(1, ExecutionStep.STATUS_APPROVED, false)).action())
                    .isEqualTo(StepProgressPolicy.Action.FILL_STEP);
            assertThat(decide(step(1, ExecutionStep.STATUS_APPROVED, false),
                    step(2, ExecutionStep.STATUS_APPROVED, true)).action())
                    .isEqualTo(StepProgressPolicy.Action.READY_TO_SUBMIT);
        }

        @Test
        @DisplayName("three-page wizard: an approved non-terminal last page never means submit")
        void threePageWizard() {
            StepProgressPolicy.Decision d = decide(
                    step(1, ExecutionStep.STATUS_APPROVED, false),
                    step(2, ExecutionStep.STATUS_APPROVED, false),
                    step(3, ExecutionStep.STATUS_APPROVED, false));

            // Not terminal, so there is more form to review — submitting here would send an
            // incomplete application.
            assertThat(d.action()).isEqualTo(StepProgressPolicy.Action.FILL_STEP);
            assertThat(d.stepNumber()).isEqualTo(4);
        }

        @Test
        @DisplayName("a review page is just a page: it still needs its own approval")
        void reviewPageIsStillAPage() {
            assertThat(decide(step(1, ExecutionStep.STATUS_APPROVED, false),
                    step(2, ExecutionStep.STATUS_PENDING_APPROVAL, true)).action())
                    .isEqualTo(StepProgressPolicy.Action.WAIT_FOR_APPROVAL);
        }

        @Test
        @DisplayName("an empty record starts at page 1")
        void emptyStartsAtOne() {
            StepProgressPolicy.Decision d = StepProgressPolicy.decide(List.of(), MAX_STEPS);
            assertThat(d.action()).isEqualTo(StepProgressPolicy.Action.FILL_STEP);
            assertThat(d.stepNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("steps are evaluated in order regardless of input order")
        void orderIndependent() {
            StepProgressPolicy.Decision d = StepProgressPolicy.decide(List.of(
                    step(2, ExecutionStep.STATUS_PENDING_APPROVAL, false),
                    step(1, ExecutionStep.STATUS_APPROVED, false)), MAX_STEPS);

            assertThat(d.action()).isEqualTo(StepProgressPolicy.Action.WAIT_FOR_APPROVAL);
            assertThat(d.stepNumber()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("terminal states and bounds")
    class TerminalStates {

        @Test
        @DisplayName("a rejection stops the run, even with later approved pages")
        void rejectionStopsTheRun() {
            StepProgressPolicy.Decision d = decide(
                    step(1, ExecutionStep.STATUS_REJECTED, false),
                    step(2, ExecutionStep.STATUS_APPROVED, false));

            assertThat(d.action()).isEqualTo(StepProgressPolicy.Action.STOP);
            assertThat(d.stepNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("a blocked or failed page stops the run")
        void blockedAndFailedStop() {
            assertThat(decide(step(1, ExecutionStep.STATUS_BLOCKED, false)).action())
                    .isEqualTo(StepProgressPolicy.Action.STOP);
            assertThat(decide(step(1, ExecutionStep.STATUS_FAILED, false)).action())
                    .isEqualTo(StepProgressPolicy.Action.STOP);
        }

        @Test
        @DisplayName("the step ceiling stops a navigation loop rather than continuing")
        void stepCeilingBoundsTheWizard() {
            ExecutionStep[] all = new ExecutionStep[MAX_STEPS];
            for (int i = 0; i < MAX_STEPS; i++) {
                all[i] = step(i + 1, ExecutionStep.STATUS_APPROVED, false);
            }
            StepProgressPolicy.Decision d = decide(all);

            assertThat(d.action()).isEqualTo(StepProgressPolicy.Action.STOP);
            assertThat(d.reason()).contains("step limit reached");
        }

        @Test
        @DisplayName("replay advances are exactly the number of already-approved pages")
        void replayAdvanceCount() {
            assertThat(StepProgressPolicy.replayAdvances(1)).isZero();
            assertThat(StepProgressPolicy.replayAdvances(2)).isEqualTo(1);
            assertThat(StepProgressPolicy.replayAdvances(4)).isEqualTo(3);
            assertThat(StepProgressPolicy.replayAdvances(0)).isZero();
        }

        @Test
        @DisplayName("null input never throws")
        void nullSafe() {
            assertThat(StepProgressPolicy.decide(null, MAX_STEPS).action())
                    .isEqualTo(StepProgressPolicy.Action.FILL_STEP);
        }
    }
}
