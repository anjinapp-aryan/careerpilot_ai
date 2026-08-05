package ai.careerpilot.execution.browser.multistep;

import ai.careerpilot.domain.ApprovalQueueEntry;
import ai.careerpilot.domain.ExecutionScreenshot;
import ai.careerpilot.domain.ExecutionStep;
import ai.careerpilot.execution.approval.ApprovalService;
import ai.careerpilot.execution.browser.PlaywrightAutomationProvider;
import ai.careerpilot.execution.browser.form.BrowserFormAutomationEngine;
import ai.careerpilot.execution.browser.form.MultiStepFormNavigator;
import ai.careerpilot.repo.ExecutionScreenshotRepository;
import ai.careerpilot.repo.ExecutionStepRepository;
import ai.careerpilot.storage.S3StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase F2 — the orchestrator runtime.
 *
 * <p>The browser is mocked throughout: every guarantee this phase makes is about <em>control
 * flow</em> — how many pages get filled, whether the lease is released, whether submit is ever
 * clicked — and those are provable without Chromium. What a real browser adds is page behaviour,
 * which is Phase F3's live-wizard work.
 */
class MultiStepExecutionOrchestratorTest {

    private static final String URL = "https://boards.example.com/jobs/1/apply";

    private PlaywrightAutomationProvider browser;
    private BrowserFormAutomationEngine engine;
    private ExecutionStepRepository steps;
    private ExecutionScreenshotRepository screenshots;
    private ApprovalService approvals;
    private S3StorageService storage;

    private final UUID executionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final List<ExecutionStep> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        browser = mock(PlaywrightAutomationProvider.class);
        engine = mock(BrowserFormAutomationEngine.class);
        steps = mock(ExecutionStepRepository.class);
        screenshots = mock(ExecutionScreenshotRepository.class);
        approvals = mock(ApprovalService.class);
        storage = mock(S3StorageService.class);

        stored.clear();
        when(steps.findByExecutionIdOrderByStepNumberAsc(executionId)).thenReturn(stored);
        when(steps.findByExecutionIdAndStepNumber(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.empty());
        when(steps.save(any(ExecutionStep.class))).thenAnswer(inv -> inv.getArgument(0));
        when(screenshots.save(any(ExecutionScreenshot.class))).thenAnswer(inv -> {
            ExecutionScreenshot s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });
        when(storage.uploadBytes(any(), anyString(), anyString(), anyString())).thenReturn("key");
        when(approvals.enqueueFormScreenshot(any(), any(), any(), any(), any(), anyString()))
                .thenReturn(Optional.of(ApprovalQueueEntry.builder().id(UUID.randomUUID()).build()));
        when(browser.currentUrl()).thenReturn(URL);
        when(browser.currentPageHtml()).thenReturn("<html><body>a normal form page</body></html>");
        when(engine.fillForm(any(), any(), any())).thenReturn(cleanFill());
        when(engine.nextStep()).thenReturn(advanceDecision());
    }

    private static BrowserFormAutomationEngine.FillOutcome cleanFill() {
        return new BrowserFormAutomationEngine.FillOutcome(true, true, "ok",
                List.of("FIRST_NAME", "EMAIL"), Map.of(), List.of(),
                Map.of("uploadsAttempted", 1, "uploadsVerified", 1));
    }

    private static MultiStepFormNavigator.Decision advanceDecision() {
        return new MultiStepFormNavigator.Decision(MultiStepFormNavigator.Action.ADVANCE,
                new MultiStepFormNavigator.Button("#next", "Next", true), "advance found");
    }

    private static MultiStepFormNavigator.Decision submitDecision() {
        return new MultiStepFormNavigator.Decision(MultiStepFormNavigator.Action.SUBMIT,
                new MultiStepFormNavigator.Button("#submit", "Submit application", true), "final");
    }

    private MultiStepExecutionOrchestrator orchestrator(boolean enabled) {
        return new MultiStepExecutionOrchestrator(browser, engine, steps, screenshots, approvals,
                storage, enabled, 10, 100L);
    }

    private MultiStepExecutionOrchestrator.Target target() {
        return new MultiStepExecutionOrchestrator.Target(executionId, userId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), URL, null);
    }

    private ExecutionStep step(int n, String status, boolean finalStep) {
        return ExecutionStep.builder().id(UUID.randomUUID()).executionId(executionId).userId(userId)
                .stepNumber(n).status(status).finalStep(finalStep).attemptCount(1).build();
    }

    @Nested
    @DisplayName("feature flag")
    class Flag {

        @Test
        @DisplayName("OFF: no lease, no browser, no database — byte-for-byte unchanged")
        void disabledDoesNothing() {
            MultiStepExecutionOrchestrator.StepOutcome out = orchestrator(false).runNextStep(target());

            assertThat(out.kind()).isEqualTo(MultiStepExecutionOrchestrator.StepOutcome.Kind.DISABLED);
            verifyNoInteractions(browser, engine, steps, screenshots, approvals, storage);
        }
    }

    @Nested
    @DisplayName("one page per run")
    class OnePagePerRun {

        @Test
        @DisplayName("a first run fills page 1 and parks it on a human")
        void firstRunFillsOnePage() {
            MultiStepExecutionOrchestrator.StepOutcome out = orchestrator(true).runNextStep(target());

            assertThat(out.kind()).isEqualTo(MultiStepExecutionOrchestrator.StepOutcome.Kind.AWAITING_APPROVAL);
            assertThat(out.stepNumber()).isEqualTo(1);
            assertThat(out.approvalId()).isNotNull();
            // Exactly one page filled, and no navigation click at all on a first page.
            verify(engine, times(1)).fillForm(any(), any(), any());
            verify(browser, never()).clickAt(anyString());
        }

        @Test
        @DisplayName("a page awaiting approval means nothing runs — no browser is opened")
        void awaitingApprovalRunsNothing() {
            stored.add(step(1, ExecutionStep.STATUS_PENDING_APPROVAL, false));

            MultiStepExecutionOrchestrator.StepOutcome out = orchestrator(true).runNextStep(target());

            assertThat(out.kind()).isEqualTo(MultiStepExecutionOrchestrator.StepOutcome.Kind.WAITING);
            verifyNoInteractions(browser);
            verify(engine, never()).fillForm(any(), any(), any());
        }

        @Test
        @DisplayName("all approved and terminal means ready to submit — and submit is NOT clicked")
        void readyToSubmitNeverClicks() {
            stored.add(step(1, ExecutionStep.STATUS_APPROVED, true));

            MultiStepExecutionOrchestrator.StepOutcome out = orchestrator(true).runNextStep(target());

            assertThat(out.kind()).isEqualTo(MultiStepExecutionOrchestrator.StepOutcome.Kind.READY_TO_SUBMIT);
            verifyNoInteractions(browser);
        }

        @Test
        @DisplayName("a rejected page stops the run without opening a browser")
        void rejectionStops() {
            stored.add(step(1, ExecutionStep.STATUS_REJECTED, false));

            assertThat(orchestrator(true).runNextStep(target()).kind())
                    .isEqualTo(MultiStepExecutionOrchestrator.StepOutcome.Kind.STOPPED);
            verifyNoInteractions(browser);
        }
    }

    @Nested
    @DisplayName("replay")
    class Replay {

        @Test
        @DisplayName("resuming at page 3 replays pages 1-2 and fills only page 3")
        void replaysApprovedPagesOnly() {
            stored.add(step(1, ExecutionStep.STATUS_APPROVED, false));
            stored.add(step(2, ExecutionStep.STATUS_APPROVED, false));

            MultiStepExecutionOrchestrator.StepOutcome out = orchestrator(true).runNextStep(target());

            assertThat(out.stepNumber()).isEqualTo(3);
            // Two replayed fills + one real fill.
            verify(engine, times(3)).fillForm(any(), any(), any());
            // Two advance clicks — never a third.
            verify(browser, times(2)).clickAt("#next");
        }

        @Test
        @DisplayName("replay never regenerates answers — it calls the same deterministic fill path")
        void replayCallsNoGenerator() {
            stored.add(step(1, ExecutionStep.STATUS_APPROVED, false));

            orchestrator(true).runNextStep(target());

            // The only fill path is the engine; there is no generator collaborator to call. The
            // determinism is structural, and this pins that the orchestrator adds no other source.
            verify(engine, atLeastOnce()).fillForm(any(), any(), any());
            verify(browser, never()).fillForm(any());
        }

        @Test
        @DisplayName("a form that changed under us stops instead of guessing")
        void replayMismatchStops() {
            stored.add(step(1, ExecutionStep.STATUS_APPROVED, false));
            // The advance control we took last time is gone.
            when(engine.nextStep()).thenReturn(MultiStepFormNavigator.Decision.unclear("no advance"));

            MultiStepExecutionOrchestrator.StepOutcome out = orchestrator(true).runNextStep(target());

            assertThat(out.kind()).isEqualTo(MultiStepExecutionOrchestrator.StepOutcome.Kind.STOPPED);
            assertThat(out.reason()).contains("replay mismatch");
            verify(approvals, never()).enqueueFormScreenshot(any(), any(), any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("a CAPTCHA met during replay stops the run")
        void captchaDuringReplayStops() {
            stored.add(step(1, ExecutionStep.STATUS_APPROVED, false));
            when(browser.currentPageHtml()).thenReturn("<div class=\"g-recaptcha\"></div>");

            MultiStepExecutionOrchestrator.StepOutcome out = orchestrator(true).runNextStep(target());

            assertThat(out.kind()).isEqualTo(MultiStepExecutionOrchestrator.StepOutcome.Kind.STOPPED);
            assertThat(out.reason()).contains("captcha");
        }
    }

    @Nested
    @DisplayName("guard integration and failure")
    class GuardAndFailure {

        @Test
        @DisplayName("a blocking gap stops the run and never queues an approval")
        void blockingGapStopsBeforeApproval() {
            when(engine.fillForm(any(), any(), any())).thenReturn(
                    new BrowserFormAutomationEngine.FillOutcome(true, false, "gaps",
                            List.of(), Map.of(), List.of("Phone"),
                            Map.of("uploadsAttempted", 0, "uploadsVerified", 0)));

            MultiStepExecutionOrchestrator.StepOutcome out = orchestrator(true).runNextStep(target());

            assertThat(out.kind()).isEqualTo(MultiStepExecutionOrchestrator.StepOutcome.Kind.STOPPED);
            assertThat(out.reason()).contains("not safe to leave");
            verify(approvals, never()).enqueueFormScreenshot(any(), any(), any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("an unverified upload stops the run")
        void unverifiedUploadStops() {
            when(engine.fillForm(any(), any(), any())).thenReturn(
                    new BrowserFormAutomationEngine.FillOutcome(true, true, "ok",
                            List.of("RESUME_UPLOAD"), Map.of(), List.of(),
                            Map.of("uploadsAttempted", 1, "uploadsVerified", 0)));

            assertThat(orchestrator(true).runNextStep(target()).kind())
                    .isEqualTo(MultiStepExecutionOrchestrator.StepOutcome.Kind.STOPPED);
        }

        @Test
        @DisplayName("a browser crash stops safely and persists state")
        void browserCrashIsHandled() {
            org.mockito.Mockito.doThrow(new RuntimeException("browser crashed"))
                    .when(browser).navigate(anyString());

            MultiStepExecutionOrchestrator.StepOutcome out = orchestrator(true).runNextStep(target());

            assertThat(out.kind()).isEqualTo(MultiStepExecutionOrchestrator.StepOutcome.Kind.STOPPED);
            assertThat(out.reason()).contains("execution failed");
            verify(steps, atLeastOnce()).save(any(ExecutionStep.class));
        }
    }

    @Nested
    @DisplayName("browser lease")
    class Lease {

        @Test
        @DisplayName("released on the success path")
        void releasedOnSuccess() {
            orchestrator(true).runNextStep(target());
            verify(browser, times(1)).logout();
        }

        @Test
        @DisplayName("released when the guard blocks")
        void releasedOnGuardBlock() {
            when(engine.fillForm(any(), any(), any())).thenReturn(
                    new BrowserFormAutomationEngine.FillOutcome(true, false, "gaps",
                            List.of(), Map.of(), List.of("Phone"), Map.of()));

            orchestrator(true).runNextStep(target());
            verify(browser, times(1)).logout();
        }

        @Test
        @DisplayName("released when the browser crashes")
        void releasedOnCrash() {
            org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(browser).navigate(anyString());

            orchestrator(true).runNextStep(target());
            verify(browser, times(1)).logout();
        }

        @Test
        @DisplayName("released even when releasing itself throws")
        void releaseFailureIsSwallowed() {
            org.mockito.Mockito.doThrow(new RuntimeException("release failed")).when(browser).logout();

            // Must not propagate: a lease-release failure cannot become the caller's problem.
            assertThat(orchestrator(true).runNextStep(target())).isNotNull();
            verify(browser, times(1)).logout();
        }

        @Test
        @DisplayName("no browser is held while awaiting approval")
        void noBrowserHeldDuringApproval() {
            MultiStepExecutionOrchestrator.StepOutcome out = orchestrator(true).runNextStep(target());

            assertThat(out.kind()).isEqualTo(MultiStepExecutionOrchestrator.StepOutcome.Kind.AWAITING_APPROVAL);
            // The run returned parked on a human AND the lease is already gone.
            verify(browser, times(1)).logout();
        }
    }

    @Nested
    @DisplayName("approval transitions")
    class Approval {

        @Test
        @DisplayName("approving a pending step marks it approved exactly once")
        void approveOnce() {
            UUID approvalId = UUID.randomUUID();
            ExecutionStep pending = step(1, ExecutionStep.STATUS_PENDING_APPROVAL, false);
            pending.setApprovalQueueEntryId(approvalId);
            when(steps.findByApprovalQueueEntryId(approvalId)).thenReturn(Optional.of(pending));

            MultiStepExecutionOrchestrator o = orchestrator(true);
            assertThat(o.markApproved(approvalId)).isPresent();
            assertThat(pending.getStatus()).isEqualTo(ExecutionStep.STATUS_APPROVED);

            // A second approval must not re-authorise anything.
            assertThat(o.markApproved(approvalId)).isEmpty();
        }

        @Test
        @DisplayName("rejecting marks the step rejected, which stops the run")
        void rejectStops() {
            UUID approvalId = UUID.randomUUID();
            ExecutionStep pending = step(1, ExecutionStep.STATUS_PENDING_APPROVAL, false);
            pending.setApprovalQueueEntryId(approvalId);
            when(steps.findByApprovalQueueEntryId(approvalId)).thenReturn(Optional.of(pending));

            assertThat(orchestrator(true).markRejected(approvalId)).isPresent();
            assertThat(pending.getStatus()).isEqualTo(ExecutionStep.STATUS_REJECTED);
        }

        @Test
        @DisplayName("the terminal page is flagged so the next decision is submit, not another page")
        void finalStepIsFlagged() {
            when(engine.nextStep()).thenReturn(submitDecision());

            orchestrator(true).runNextStep(target());

            verify(steps, atLeastOnce()).save(org.mockito.ArgumentMatchers.argThat(
                    s -> s != null && s.isFinalStep()));
        }
    }
}
