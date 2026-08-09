package ai.careerpilot.execution.browser.multistep;

import ai.careerpilot.domain.ApprovalQueueEntry;
import ai.careerpilot.domain.ExecutionScreenshot;
import ai.careerpilot.domain.ExecutionStep;
import ai.careerpilot.execution.approval.ApprovalService;
import ai.careerpilot.execution.browser.FrameAwareCaptchaCheck;
import ai.careerpilot.execution.browser.PlaywrightAutomationProvider;
import ai.careerpilot.execution.browser.form.BrowserFormAutomationEngine;
import ai.careerpilot.execution.browser.form.MultiStepFormNavigator;
import ai.careerpilot.repo.ExecutionScreenshotRepository;
import ai.careerpilot.repo.ExecutionStepRepository;
import ai.careerpilot.storage.S3StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase F2 — the runtime that drives one page of a multi-step employer form, then stops.
 *
 * <p><b>This class orchestrates; it implements nothing.</b> Filling is
 * {@link BrowserFormAutomationEngine}, navigation choice is {@link MultiStepFormNavigator}, the
 * decision to leave a page is {@link StepAdvanceGuard}, the decision about what may run at all is
 * {@link StepProgressPolicy}, approval is {@code ApprovalService}, and the browser is the existing
 * provider and lease pool. None of them were modified for this phase.
 *
 * <h2>One lease per page — the constraint everything follows from</h2>
 * A human approval is asynchronous and the pool runs with a single lease on a 180-second TTL, so a
 * browser context cannot be held across one. {@link #runNextStep} therefore always terminates by
 * releasing the lease, whatever happened, and the execution genuinely pauses with no browser open.
 *
 * <h2>Replay instead of a held session</h2>
 * Resuming re-navigates and re-fills the already-approved pages before touching a new one. This is
 * safe because <b>the fill path is structurally deterministic</b>, not because this class takes care
 * to be: {@code AnswerResolver} calls no AI, and {@code EmployerAnswerService} returns only
 * human-approved answers. Replay therefore cannot regenerate an answer, change a value, or
 * reclassify a field — there is no code path through which it could. What page 1 typed on the run a
 * reviewer approved is what it types on every replay.
 *
 * <p><b>SUBMIT is never clicked here.</b> This orchestrator only ever performs {@code ADVANCE}, and
 * only up to the page it was asked to reach. Submission remains the existing approved path.
 *
 * <p>Gated by {@code browser.automation.multi-step.enabled} (default {@code false}). With it off,
 * {@link #runNextStep} returns {@link StepOutcome.Kind#DISABLED} without acquiring a lease,
 * touching the database, or opening a browser — production behaviour is byte-for-byte unchanged.
 */
@Service
public class MultiStepExecutionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MultiStepExecutionOrchestrator.class);

    private static final String SCREENSHOT_PHASE_STEP = "STEP";

    private final PlaywrightAutomationProvider browser;
    private final BrowserFormAutomationEngine formEngine;
    private final ExecutionStepRepository steps;
    private final ExecutionScreenshotRepository screenshots;
    private final ApprovalService approvalService;
    private final S3StorageService storage;

    /** Owned, not injected: this application has no ObjectMapper bean (see Phase C). */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final boolean enabled;
    private final int maxSteps;
    private final long settleMs;

    public MultiStepExecutionOrchestrator(
            PlaywrightAutomationProvider browser,
            BrowserFormAutomationEngine formEngine,
            ExecutionStepRepository steps,
            ExecutionScreenshotRepository screenshots,
            ApprovalService approvalService,
            S3StorageService storage,
            @Value("${browser.automation.multi-step.enabled:false}") boolean enabled,
            @Value("${browser.automation.multi-step.max-steps:10}") int maxSteps,
            @Value("${browser.automation.multi-step.settle-ms:1500}") long settleMs) {
        this.browser = browser;
        this.formEngine = formEngine;
        this.steps = steps;
        this.screenshots = screenshots;
        this.approvalService = approvalService;
        this.storage = storage;
        this.enabled = enabled;
        this.maxSteps = maxSteps;
        this.settleMs = settleMs;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Everything the orchestrator needs, supplied by the caller — it resolves no packages itself. */
    public record Target(UUID executionId, UUID userId, UUID jobId, UUID applicationPackageId,
                         UUID sessionId, String applyUrl,
                         BrowserFormAutomationEngine.DocumentPaths documents) {}

    /** What happened. Never an exception — a failure is a persisted state, not a thrown error. */
    public record StepOutcome(Kind kind, int stepNumber, UUID approvalId, String reason) {

        public enum Kind {
            /** A page was filled, captured, and parked on a human. */
            AWAITING_APPROVAL,
            /** Every page is approved and the last was terminal. */
            READY_TO_SUBMIT,
            /** A page is already awaiting approval — nothing was done. */
            WAITING,
            /** Stopped safely. The reason says why; a human must act. */
            STOPPED,
            /** Feature flag off. */
            DISABLED
        }

        static StepOutcome of(Kind kind, int step, String reason) {
            return new StepOutcome(kind, step, null, reason);
        }
    }

    /**
     * Run at most one page, then stop.
     *
     * <p>Never throws and never continues past the page it was asked to fill. The browser lease is
     * released on every exit path, including every failure path.
     */
    public StepOutcome runNextStep(Target target) {
        if (!enabled) {
            return StepOutcome.of(StepOutcome.Kind.DISABLED, 0, "multi-step navigation is disabled");
        }
        if (target == null || target.executionId() == null || target.applyUrl() == null) {
            return StepOutcome.of(StepOutcome.Kind.STOPPED, 0, "incomplete target");
        }

        List<ExecutionStep> existing = steps.findByExecutionIdOrderByStepNumberAsc(target.executionId());
        StepProgressPolicy.Decision decision = StepProgressPolicy.decide(existing, maxSteps);
        log.info("MULTISTEP decision executionId={} action={} stepNumber={} reason={}",
                target.executionId(), decision.action(), decision.stepNumber(), decision.reason());

        switch (decision.action()) {
            case WAIT_FOR_APPROVAL -> {
                return StepOutcome.of(StepOutcome.Kind.WAITING, decision.stepNumber(), decision.reason());
            }
            case READY_TO_SUBMIT -> {
                return StepOutcome.of(StepOutcome.Kind.READY_TO_SUBMIT, decision.stepNumber(), decision.reason());
            }
            case STOP -> {
                return StepOutcome.of(StepOutcome.Kind.STOPPED, decision.stepNumber(), decision.reason());
            }
            case FILL_STEP -> {
                return fillStep(target, decision.stepNumber(), existing);
            }
        }
        return StepOutcome.of(StepOutcome.Kind.STOPPED, 0, "unreachable policy action");
    }

    /**
     * Phase F3 — does this approval belong to a multi-step run?
     *
     * <p>The approval worker asks this to choose between the multi-step path and the pre-existing
     * single-page {@code finalizeSubmit}. <b>Returns false without touching the repository when the
     * flag is off</b>, which is what makes "flag off ⇒ zero repository reads or writes" true rather
     * than merely intended: a disabled deployment never learns this table exists.
     */
    public boolean isMultiStepApproval(UUID approvalQueueEntryId) {
        if (!enabled || approvalQueueEntryId == null) return false;
        try {
            return steps.findByApprovalQueueEntryId(approvalQueueEntryId).isPresent();
        } catch (Exception e) {
            // Fail closed toward the existing behaviour: an unreadable step table must route the
            // approval down the well-tested single-page path, never into a half-known multi-step one.
            log.warn("MULTISTEP ownership check failed approvalId={}: {}", approvalQueueEntryId, e.toString());
            return false;
        }
    }

    /** Called by the approval worker when a reviewer approves a step's screenshot. */
    public Optional<ExecutionStep> markApproved(UUID approvalQueueEntryId) {
        return transition(approvalQueueEntryId, ExecutionStep.STATUS_APPROVED);
    }

    /** Called by the approval worker on rejection. The run stops; {@code decide} enforces that. */
    public Optional<ExecutionStep> markRejected(UUID approvalQueueEntryId) {
        return transition(approvalQueueEntryId, ExecutionStep.STATUS_REJECTED);
    }

    private Optional<ExecutionStep> transition(UUID approvalQueueEntryId, String status) {
        if (!enabled || approvalQueueEntryId == null) return Optional.empty();
        Optional<ExecutionStep> found = steps.findByApprovalQueueEntryId(approvalQueueEntryId);
        if (found.isEmpty()) return Optional.empty();
        ExecutionStep step = found.get();
        // Only a step genuinely parked on a human may transition. Re-approving an already-decided
        // step would let one approval authorise a second page.
        if (!step.isAwaitingApproval()) return Optional.empty();
        step.setStatus(status);
        step.setUpdatedAt(Instant.now());
        log.info("MULTISTEP step transition executionId={} stepNumber={} status={}",
                step.getExecutionId(), step.getStepNumber(), status);
        return Optional.of(steps.save(step));
    }

    // ── the single page ───────────────────────────────────────────────────────────────────────

    private StepOutcome fillStep(Target target, int stepNumber, List<ExecutionStep> approved) {
        long leaseAcquire = System.currentTimeMillis();
        long replayMs = 0L;
        long fillMs = 0L;
        try {
            browser.navigate(target.applyUrl());
            browser.waitForStable(settleMs);
            log.info("MULTISTEP leaseAcquire executionId={} stepNumber={} pageUrl={}",
                    target.executionId(), stepNumber, safeUrl());

            // ── replay ──
            int advances = StepProgressPolicy.replayAdvances(stepNumber);
            if (advances > 0) {
                long replayStart = System.currentTimeMillis();
                Optional<String> mismatch = replay(target, advances);
                replayMs = System.currentTimeMillis() - replayStart;
                log.info("MULTISTEP replay executionId={} stepNumber={} advances={} replayDuration={}ms",
                        target.executionId(), stepNumber, advances, replayMs);
                if (mismatch.isPresent()) {
                    return stop(target, stepNumber, "replay mismatch: " + mismatch.get());
                }
            }

            // ── fill the page we are actually here for ──
            long fillStart = System.currentTimeMillis();
            BrowserFormAutomationEngine.FillOutcome outcome =
                    formEngine.fillForm(target.userId(), target.sessionId(), documents(target));
            fillMs = System.currentTimeMillis() - fillStart;
            log.info("MULTISTEP fill executionId={} stepNumber={} filled={} blocking={} fillDuration={}ms",
                    target.executionId(), stepNumber, outcome.filled().size(),
                    outcome.blockingGaps().size(), fillMs);

            // ── guard ──
            MultiStepFormNavigator.Decision navigation = formEngine.nextStep();
            StepAdvanceGuard.Observation observation = observe(target, outcome, stepNumber, approved);
            StepAdvanceGuard.Verdict verdict = StepAdvanceGuard.evaluate(observation);
            log.info("MULTISTEP guardDecision executionId={} stepNumber={} safe={} blockers={}",
                    target.executionId(), stepNumber, verdict.safe(), verdict.blockers());

            boolean finalStep = navigation.action() == MultiStepFormNavigator.Action.SUBMIT;
            String screenshotKey = captureStep(target, stepNumber);
            StepApprovalBundle bundle = bundle(stepNumber, finalStep, screenshotKey, outcome,
                    navigation, verdict);

            if (!verdict.safe()) {
                // Persist the evidence anyway: a blocked page is exactly the one a human needs to
                // see, and discarding it would leave them with a status and no explanation.
                persist(target, stepNumber, ExecutionStep.STATUS_BLOCKED, finalStep, bundle, null, null);
                return StepOutcome.of(StepOutcome.Kind.STOPPED, stepNumber,
                        "page not safe to leave: " + String.join("; ", verdict.blockers()));
            }

            ExecutionScreenshot shot = screenshots.save(ExecutionScreenshot.builder()
                    .executionId(target.executionId()).userId(target.userId()).jobId(target.jobId())
                    .storageKey(screenshotKey).phase(SCREENSHOT_PHASE_STEP)
                    .build());

            Optional<ApprovalQueueEntry> entry = approvalService.enqueueFormScreenshot(
                    target.userId(), target.jobId(), target.applicationPackageId(),
                    target.executionId(), shot.getId(), "REVIEW");
            if (entry.isEmpty()) {
                return stop(target, stepNumber, "approval enqueue failed (approval disabled?)");
            }

            persist(target, stepNumber, ExecutionStep.STATUS_PENDING_APPROVAL, finalStep, bundle,
                    shot.getId(), entry.get().getId());
            log.info("MULTISTEP approvalBundleCreated executionId={} stepNumber={} approvalId={} finalStep={}",
                    target.executionId(), stepNumber, entry.get().getId(), finalStep);

            return new StepOutcome(StepOutcome.Kind.AWAITING_APPROVAL, stepNumber,
                    entry.get().getId(), "page " + stepNumber + " filled and awaiting human approval");

        } catch (Exception e) {
            // Browser crash, timeout, navigation failure — all one thing here: stop, persist, and
            // let the lease go.
            log.warn("MULTISTEP failure executionId={} stepNumber={}: {}",
                    target.executionId(), stepNumber, e.toString());
            return stop(target, stepNumber, "execution failed: " + e);
        } finally {
            // ALWAYS. The single production lease must never be held across an approval, and a
            // failure path that leaks it would wedge every later run until the TTL sweep.
            try {
                browser.logout();
            } catch (Exception e) {
                log.warn("MULTISTEP lease release failed executionId={}: {}",
                        target.executionId(), e.toString());
            }
            log.info("MULTISTEP leaseRelease executionId={} stepNumber={} totalMs={} replayMs={} fillMs={}",
                    target.executionId(), stepNumber,
                    System.currentTimeMillis() - leaseAcquire, replayMs, fillMs);
        }
    }

    /**
     * Re-fill and advance past the already-approved pages.
     *
     * @return a description of the mismatch, or empty when replay reached the target page
     */
    private Optional<String> replay(Target target, int advances) {
        for (int i = 1; i <= advances; i++) {
            // Re-filling uses the identical deterministic path as the original run. It cannot
            // produce a different value: no AI is called anywhere beneath this line.
            formEngine.fillForm(target.userId(), target.sessionId(), documents(target));

            MultiStepFormNavigator.Decision decision = formEngine.nextStep();
            if (decision.action() != MultiStepFormNavigator.Action.ADVANCE) {
                // The form no longer offers the advance we took last time — it changed under us.
                // Refusing is the only safe response; guessing would fill a page nobody reviewed.
                return Optional.of("expected an advance control on replayed page " + i
                        + " but found " + decision.action() + " (" + decision.reason() + ")");
            }
            browser.clickAt(decision.button().selector());
            browser.waitForStable(settleMs);

            // P0.2 — iframe-aware: reuses the same DISCOVER_FRAME_REPORT-backed check as
            // GuestApplyAutomationService, so a CAPTCHA that only appears inside a same-origin
            // iframe on a replayed page is caught here too, not just on the top document.
            if (FrameAwareCaptchaCheck.run(browser).detected()) {
                return Optional.of("captcha or login wall encountered while replaying page " + i);
            }
        }
        return Optional.empty();
    }

    private StepAdvanceGuard.Observation observe(Target target,
                                                 BrowserFormAutomationEngine.FillOutcome outcome,
                                                 int stepNumber, List<ExecutionStep> approved) {
        boolean captcha;
        String urlNow;
        try {
            // P0.2 — iframe-aware, same shared check as replay() above.
            captcha = FrameAwareCaptchaCheck.run(browser).detected();
        } catch (Exception e) {
            captcha = true;   // could not check ⇒ treated as present; the guard fails closed
        }
        try {
            urlNow = browser.currentUrl();
        } catch (Exception e) {
            urlNow = null;
        }

        Object expected = outcome.evidence().get("uploadsAttempted");
        Object verified = outcome.evidence().get("uploadsVerified");
        int attempt = approved.stream()
                .filter(s -> s.getStepNumber() == stepNumber)
                .mapToInt(ExecutionStep::getAttemptCount).max().orElse(0) + 1;

        return new StepAdvanceGuard.Observation(
                outcome.blockingGaps(),
                validationErrors(outcome),
                asInt(expected),
                asInt(verified),
                captcha,
                target.applyUrl(),
                urlNow,
                true,
                !captcha,
                attempt);
    }

    private static List<String> validationErrors(BrowserFormAutomationEngine.FillOutcome outcome) {
        Object errors = outcome.evidence().get("validationErrors");
        if (errors instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) if (o != null) out.add(String.valueOf(o));
            return out;
        }
        return List.of();
    }

    private static int asInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static BrowserFormAutomationEngine.DocumentPaths documents(Target target) {
        return target.documents() == null
                ? BrowserFormAutomationEngine.DocumentPaths.none() : target.documents();
    }

    private StepApprovalBundle bundle(int stepNumber, boolean finalStep, String screenshotKey,
                                      BrowserFormAutomationEngine.FillOutcome outcome,
                                      MultiStepFormNavigator.Decision navigation,
                                      StepAdvanceGuard.Verdict verdict) {
        Map<String, String> filled = new LinkedHashMap<>();
        for (String field : outcome.filled()) {
            // Field identities only — the reviewer sees values on the screenshot, and a log-bound
            // bundle must not become a second copy of the candidate's personal data.
            filled.put(field, "filled");
        }
        return new StepApprovalBundle(
                stepNumber,
                null,                                   // total steps: never estimated
                finalStep,
                safeUrl(),
                screenshotKey,
                filled,
                outcome.filled(),
                outcome.skipped(),
                List.of(),
                List.of(),
                outcome.blockingGaps(),
                verdict.blockers(),
                null,
                navigation.button() == null ? null : navigation.button().label(),
                navigation.action().name());
    }

    private String captureStep(Target target, int stepNumber) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("multistep-", ".png");
            browser.captureScreenshot(tmp);
            byte[] bytes = Files.readAllBytes(tmp);
            return storage.uploadBytes(bytes,
                    "execution-screenshots/" + target.executionId(),
                    "step-" + stepNumber + ".png", "image/png");
        } catch (Exception e) {
            // Evidence capture must not cost the run its safe stop.
            log.warn("MULTISTEP screenshot failed executionId={} stepNumber={}: {}",
                    target.executionId(), stepNumber, e.toString());
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                    // Temp file cleanup is best effort.
                }
            }
        }
    }

    private void persist(Target target, int stepNumber, String status, boolean finalStep,
                         StepApprovalBundle bundle, UUID screenshotId, UUID approvalId) {
        try {
            Instant now = Instant.now();
            ExecutionStep step = steps.findByExecutionIdAndStepNumber(target.executionId(), stepNumber)
                    .orElseGet(() -> ExecutionStep.builder()
                            .executionId(target.executionId()).userId(target.userId())
                            .stepNumber(stepNumber).attemptCount(0).createdAt(now).build());
            step.setStatus(status);
            step.setFinalStep(finalStep);
            step.setPageUrl(bundle == null ? safeUrl() : bundle.pageUrl());
            step.setScreenshotId(screenshotId);
            step.setApprovalQueueEntryId(approvalId);
            step.setAttemptCount(step.getAttemptCount() + 1);
            step.setBundleJson(bundle == null ? null : writeJson(bundle));
            step.setUpdatedAt(now);
            steps.save(step);
        } catch (Exception e) {
            log.warn("MULTISTEP step persist failed executionId={} stepNumber={}: {}",
                    target.executionId(), stepNumber, e.toString());
        }
    }

    private StepOutcome stop(Target target, int stepNumber, String reason) {
        persist(target, stepNumber, ExecutionStep.STATUS_FAILED, false, null, null, null);
        return StepOutcome.of(StepOutcome.Kind.STOPPED, stepNumber, reason);
    }

    private String safeUrl() {
        try {
            return browser.currentUrl();
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(StepApprovalBundle bundle) {
        try {
            return objectMapper.writeValueAsString(bundle.snapshot());
        } catch (Exception e) {
            return null;
        }
    }
}
