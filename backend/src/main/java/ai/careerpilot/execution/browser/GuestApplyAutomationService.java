package ai.careerpilot.execution.browser;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.ApplicationPackage;
import ai.careerpilot.domain.ApplicationSubmissionSession;
import ai.careerpilot.domain.ApprovalQueueEntry;
import ai.careerpilot.domain.CoverLetter;
import ai.careerpilot.domain.ExecutionScreenshot;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.domain.User;
import ai.careerpilot.execution.browser.form.BrowserFormAutomationEngine;
import ai.careerpilot.execution.browser.multistep.MultiStepExecutionOrchestrator;
import ai.careerpilot.repo.ApplicationPackageRepository;
import ai.careerpilot.repo.ApplicationSubmissionSessionRepository;
import ai.careerpilot.repo.CoverLetterRepository;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.execution.approval.ApprovalService;
import ai.careerpilot.execution.ats.ATSConnector;
import ai.careerpilot.execution.ats.GuestApplyEligibility;
import ai.careerpilot.repo.ExecutionScreenshotRepository;
import ai.careerpilot.repo.UserRepository;
import ai.careerpilot.storage.S3StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Gap D — orchestrates one real guest/no-login apply attempt for a {@link
 * ApplicationExecution} whose resolved {@link ATSConnector} is {@link GuestApplyEligibility}. Two
 * phases, mirroring the two-gate design in the brief:
 *
 * <ol>
 *   <li>{@link #attemptFill} — navigate, abort immediately on any CAPTCHA/login-wall marker, fill
 *       only verifiably real applicant fields (never fabricated), screenshot the filled form,
 *       upload the screenshot, and enqueue a NEW {@link ApprovalQueueEntry#TYPE_FORM_SCREENSHOT}
 *       approval. The browser context is always closed at the end of this call — a human approval
 *       can take arbitrarily long, so nothing holds a live browser session open across the wait.</li>
 *   <li>{@link #finalizeSubmit} — invoked only after a human approves that specific screenshot
 *       (see {@code FormApprovalExecutionWorker}). Re-navigates, re-detects CAPTCHA/login wall
 *       (defense in depth — the page may have changed since the screenshot), refills the same real
 *       fields, and performs the actual submit click.</li>
 * </ol>
 *
 * <p><b>Guest-apply enforcement is hardcoded</b> ({@link GuestApplyEligibility}), not driven by
 * {@code browser.automation.guest-apply-only} — that flag is diagnostics-visibility only (see its
 * javadoc). Both phases re-check eligibility so this class can never be pointed at a login-required
 * connector by a caller mistake.
 */
@Service
public class GuestApplyAutomationService {

    private static final Logger log = LoggerFactory.getLogger(GuestApplyAutomationService.class);

    // ── Phase 7.16.3 — Screenshot Timeline. BEFORE_SUBMIT is the pre-existing approval screenshot;
    // AFTER_SUBMIT/FAILURE are new, best-effort captures that never block or fail the outcome they
    // annotate (screenshot capture errors are swallowed, logged, and otherwise ignored). ──
    private static final String SCREENSHOT_PHASE_BEFORE_SUBMIT = "BEFORE_SUBMIT";
    private static final String SCREENSHOT_PHASE_AFTER_SUBMIT = "AFTER_SUBMIT";
    private static final String SCREENSHOT_PHASE_FAILURE = "FAILURE";

    private final PlaywrightAutomationProvider browser;
    private final BrowserAutomationMetrics metrics;
    private final S3StorageService storage;
    private final ApprovalService approvalService;
    private final ExecutionScreenshotRepository screenshots;
    private final UserRepository users;
    private final boolean guestApplyOnlyFlag;

    // ── Phase 12C — collaborators for the universal form engine. All resolved through
    // ObjectProvider-free direct injection because every one is an unconditional bean; the engine
    // itself carries the feature flag, so an off deployment simply short-circuits inside it. ──
    private final BrowserFormAutomationEngine formEngine;
    private final ApplicationPackageRepository packages;
    private final ResumeRepository resumes;
    private final CoverLetterRepository coverLetters;
    private final ApplicationSubmissionSessionRepository submissionSessions;

    /** Phase F3 — optional multi-step orchestrator; absent or disabled leaves this class unchanged. */
    private final org.springframework.beans.factory.ObjectProvider<
            ai.careerpilot.execution.browser.multistep.MultiStepExecutionOrchestrator> multiStep;

    public GuestApplyAutomationService(PlaywrightAutomationProvider browser, BrowserAutomationMetrics metrics,
                                       S3StorageService storage, ApprovalService approvalService,
                                       ExecutionScreenshotRepository screenshots, UserRepository users,
                                       BrowserFormAutomationEngine formEngine,
                                       ApplicationPackageRepository packages,
                                       ResumeRepository resumes,
                                       CoverLetterRepository coverLetters,
                                       ApplicationSubmissionSessionRepository submissionSessions,
                                       // Phase F3 — optional: the orchestrator has its own
                                       // independent flag, and this service must construct and
                                       // behave identically when it is absent or disabled.
                                       org.springframework.beans.factory.ObjectProvider<
                                               ai.careerpilot.execution.browser.multistep
                                                       .MultiStepExecutionOrchestrator> multiStep,
                                       @Value("${browser.automation.guest-apply-only:true}") boolean guestApplyOnlyFlag) {
        this.multiStep = multiStep;
        this.browser = browser;
        this.metrics = metrics;
        this.storage = storage;
        this.approvalService = approvalService;
        this.screenshots = screenshots;
        this.users = users;
        this.formEngine = formEngine;
        this.packages = packages;
        this.resumes = resumes;
        this.coverLetters = coverLetters;
        this.submissionSessions = submissionSessions;
        this.guestApplyOnlyFlag = guestApplyOnlyFlag;
    }

    /** Diagnostics-visibility only — see class javadoc. Real enforcement is {@link GuestApplyEligibility}. */
    public boolean isGuestApplyOnlyFlagEnabled() {
        return guestApplyOnlyFlag;
    }

    public boolean isEligible(ATSConnector connector) {
        return connector != null && GuestApplyEligibility.isEligible(connector.name());
    }

    /** Result of one automation attempt. Never throws to the caller — always a value. */
    public record AttemptOutcome(Kind kind, String reason, UUID approvalId, String confirmationReference) {
        public enum Kind { AWAITING_APPROVAL, ABORTED, SUBMITTED, ERROR }

        public static AttemptOutcome awaitingApproval(UUID approvalId) {
            return new AttemptOutcome(Kind.AWAITING_APPROVAL, null, approvalId, null);
        }
        public static AttemptOutcome aborted(String reason) {
            return new AttemptOutcome(Kind.ABORTED, reason, null, null);
        }
        public static AttemptOutcome submitted(String confirmationReference) {
            return new AttemptOutcome(Kind.SUBMITTED, null, null, confirmationReference);
        }
        public static AttemptOutcome error(String reason) {
            return new AttemptOutcome(Kind.ERROR, reason, null, null);
        }
    }

    public AttemptOutcome attemptFill(ApplicationExecution exec, Job job, ATSConnector connector) {
        if (!isEligible(connector)) {
            return AttemptOutcome.aborted("connector '" + connector.name() + "' is not guest-apply eligible");
        }
        String url = applyUrl(job);
        if (url == null) {
            return AttemptOutcome.aborted("job has no reachable apply URL");
        }
        metrics.recordRequest();
        long start = System.currentTimeMillis();
        try {
            browser.navigate(url);
            if (CaptchaLoginDetector.looksLikeCaptchaOrLogin(browser.currentPageHtml())) {
                metrics.recordCaptchaOrLoginWallDetected();
                log.info("GUEST_APPLY captcha/login wall detected execution={} — routed to human review", exec.getId());
                return AttemptOutcome.aborted("captcha or login wall detected — routed to human review");
            }
            Map<String, String> filled = resolveFields(connector.extractForm(job), exec.getUserId());
            browser.fillForm(filled);

            // Phase 12C — the universal form engine runs AFTER the connector's own known-good
            // selectors, never instead of them. The connector schema is hand-verified for its ATS;
            // discovery is general. Running the specific one first means the engine can only ever
            // ADD fields (resume upload, cover letter, screening questions) that the connector
            // never knew about — it cannot regress a field the connector already filled correctly.
            // With the flag off this is a no-op and attemptFill is byte-identical to Phase 12B.
            FormFillReport formReport = runFormEngine(exec);

            // A required field we cannot honestly fill means this application would be incomplete.
            // Abort to human review rather than parking an approval: asking someone to approve a
            // screenshot of a form that is missing its resume invites them to approve a submission
            // that will either be rejected by the employer's own validation or — worse — delivered
            // incomplete under the candidate's real name. Only reachable when the form engine ran.
            if (formReport.hasBlockingGaps()) {
                log.info("GUEST_APPLY aborting execution={} — {} required field(s) have no verified value: {}",
                        exec.getId(), formReport.blockingGaps().size(), formReport.blockingGaps());
                return AttemptOutcome.aborted("required fields could not be filled from verified data: "
                        + String.join("; ", formReport.blockingGaps()));
            }

            Path tmp = Files.createTempFile("exec-screenshot-", ".png");
            try {
                browser.captureScreenshot(tmp);
                metrics.recordScreenshotCaptured();
                byte[] bytes = Files.readAllBytes(tmp);
                String key = storage.uploadBytes(bytes, "execution-screenshots/" + exec.getId(), "form.png", "image/png");
                ExecutionScreenshot shot = screenshots.save(ExecutionScreenshot.builder()
                        .executionId(exec.getId()).userId(exec.getUserId()).jobId(exec.getJobId())
                        .storageKey(key)
                        .phase(SCREENSHOT_PHASE_BEFORE_SUBMIT)
                        .build());
                Optional<ApprovalQueueEntry> entry = approvalService.enqueueFormScreenshot(
                        exec.getUserId(), exec.getJobId(), exec.getApplicationPackageId(),
                        exec.getId(), shot.getId(), "REVIEW");
                if (entry.isEmpty()) {
                    return AttemptOutcome.aborted("form-screenshot approval enqueue failed (approval disabled?)");
                }
                shot.setApprovalQueueEntryId(entry.get().getId());
                screenshots.save(shot);
                metrics.recordFormScreenshotApprovalPending();
                log.info("GUEST_APPLY form filled + screenshot pending approval execution={} approval={}",
                        exec.getId(), entry.get().getId());
                return AttemptOutcome.awaitingApproval(entry.get().getId());
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            metrics.recordFailure();
            log.warn("GUEST_APPLY attemptFill error execution={}: {}", exec.getId(), e.toString());
            captureFailureScreenshot(exec);
            return AttemptOutcome.error(e.toString());
        } finally {
            safeLogout();
            metrics.recordLatency(System.currentTimeMillis() - start);
        }
    }

    public AttemptOutcome finalizeSubmit(ApplicationExecution exec, Job job, ATSConnector connector) {
        if (!isEligible(connector)) {
            return AttemptOutcome.aborted("connector '" + connector.name() + "' is not guest-apply eligible");
        }
        String url = applyUrl(job);
        if (url == null) {
            return AttemptOutcome.aborted("job has no reachable apply URL");
        }
        long start = System.currentTimeMillis();
        try {
            browser.navigate(url);
            if (CaptchaLoginDetector.looksLikeCaptchaOrLogin(browser.currentPageHtml())) {
                metrics.recordCaptchaOrLoginWallDetected();
                log.info("GUEST_APPLY captcha/login wall detected on resubmit execution={} — routed to human review", exec.getId());
                return AttemptOutcome.aborted("captcha or login wall detected on resubmit — routed to human review");
            }
            Map<String, String> filled = resolveFields(connector.extractForm(job), exec.getUserId());
            browser.fillForm(filled);

            // Phase 12C — the engine MUST run here too. finalizeSubmit re-navigates to a fresh page
            // after the human approval, so nothing the fill phase uploaded still exists; without
            // this the resume would be attached to the screenshot the human approved and then
            // absent from the application actually submitted.
            FormFillReport formReport = runFormEngine(exec);
            if (formReport.hasBlockingGaps()) {
                // Refusing at the last moment is the correct outcome: the click is irreversible and
                // the form is provably incomplete. Routed to human review, never submitted anyway.
                log.warn("GUEST_APPLY refusing submit execution={} — required field(s) unfilled at finalize: {}",
                        exec.getId(), formReport.blockingGaps());
                return AttemptOutcome.aborted("required fields could not be filled at submit time: "
                        + String.join("; ", formReport.blockingGaps()));
            }

            String reference = connector.submit(job, Map.of());
            metrics.recordRealSubmission();
            metrics.recordConfirmationCaptured();
            log.info("GUEST_APPLY real submit execution={} connector={}", exec.getId(), connector.name());
            captureScreenshot(exec, SCREENSHOT_PHASE_AFTER_SUBMIT);
            return AttemptOutcome.submitted(reference);
        } catch (Exception e) {
            metrics.recordFailure();
            log.warn("GUEST_APPLY finalizeSubmit error execution={}: {}", exec.getId(), e.toString());
            captureFailureScreenshot(exec);
            return AttemptOutcome.error(e.toString());
        } finally {
            safeLogout();
            metrics.recordFormScreenshotApprovalResolved();
            long elapsed = System.currentTimeMillis() - start;
            metrics.recordLatency(elapsed);
            // Phase 12B — tracked separately from the shared attempt latency above: a fill and a
            // submit have very different expected durations, and averaging them together hides a
            // slow submit behind fast fills.
            metrics.recordSubmitLatency(elapsed);
        }
    }

    // ── Phase F3 — multi-step entry point ──────────────────────────────────────────────────────

    /**
     * Phase F3 — run the next page of a multi-step form for this execution.
     *
     * <p>Purely a composition seam: it resolves the same documents and submission session that
     * {@link #runFormEngine} already resolves, hands them to
     * {@code MultiStepExecutionOrchestrator}, and returns its outcome untouched. It exists here
     * rather than in the orchestrator because the orchestrator deliberately owns no package,
     * resume or session logic — and duplicating that resolution would be the second copy this
     * phase forbids.
     *
     * <p><b>Adds nothing to the single-page path.</b> {@link #attemptFill} and
     * {@link #finalizeSubmit} are unchanged; nothing calls this method unless a multi-step
     * {@code ExecutionStep} already exists for the approval being processed.
     */
    public MultiStepExecutionOrchestrator.StepOutcome runMultiStep(ApplicationExecution exec, Job job) {
        MultiStepExecutionOrchestrator engine = multiStep == null ? null : multiStep.getIfAvailable();
        if (engine == null || !engine.isEnabled()) {
            return new MultiStepExecutionOrchestrator.StepOutcome(
                    MultiStepExecutionOrchestrator.StepOutcome.Kind.DISABLED, 0, null,
                    "multi-step navigation is disabled");
        }
        String url = applyUrl(job);
        if (url == null) {
            return new MultiStepExecutionOrchestrator.StepOutcome(
                    MultiStepExecutionOrchestrator.StepOutcome.Kind.STOPPED, 0, null,
                    "job has no reachable apply URL");
        }

        Path resumeTmp = null;
        try {
            ApplicationPackage pkg = exec.getApplicationPackageId() == null ? null
                    : packages.findById(exec.getApplicationPackageId()).orElse(null);
            resumeTmp = materialiseResume(pkg);

            BrowserFormAutomationEngine.DocumentPaths docs = new BrowserFormAutomationEngine.DocumentPaths(
                    resumeTmp,
                    resumeTmp == null ? null : resumeTmp.getFileName().toString(),
                    // Same deliberate omission as the single-page path: a cover letter is prose and
                    // this platform has no PDF renderer, so a cover-letter FILE input is reported as
                    // an honest gap rather than filled with a .txt.
                    null,
                    coverLetterTextOf(pkg));

            return engine.runNextStep(new MultiStepExecutionOrchestrator.Target(
                    exec.getId(), exec.getUserId(), exec.getJobId(), exec.getApplicationPackageId(),
                    latestSessionId(exec.getUserId(), exec.getJobId()), url, docs));
        } catch (Exception e) {
            log.warn("GUEST_APPLY multi-step run failed execution={}: {}", exec.getId(), e.toString());
            return new MultiStepExecutionOrchestrator.StepOutcome(
                    MultiStepExecutionOrchestrator.StepOutcome.Kind.STOPPED, 0, null,
                    "multi-step run failed: " + e);
        } finally {
            deleteQuietly(resumeTmp);
        }
    }

    // ── Phase 12C — Universal Form Automation Engine integration ───────────────────────────────

    /**
     * What the form engine did, in a shape {@code attemptFill} can act on and attach to evidence.
     * A disabled engine yields {@link #skipped()}, which is indistinguishable from Phase 12B.
     */
    public record FormFillReport(boolean ran, boolean success, List<String> filled,
                                 Map<String, String> skipped, List<String> blockingGaps) {
        /** The engine did not run — flag off, or it failed and we degraded to connector-only. */
        public static FormFillReport notRun() {
            return new FormFillReport(false, false, List.of(), Map.of(), List.of());
        }

        public boolean hasBlockingGaps() {
            return ran && !blockingGaps.isEmpty();
        }
    }

    /**
     * Runs the universal form engine, resolving the resume and cover letter from the execution's
     * {@code ApplicationPackage}. Never throws — a form-engine failure degrades to "the connector's
     * own fields were filled", which is exactly the Phase 12B behaviour.
     */
    private FormFillReport runFormEngine(ApplicationExecution exec) {
        if (formEngine == null || !formEngine.isEnabled()) return FormFillReport.notRun();
        Path resumeTmp = null;
        try {
            ApplicationPackage pkg = exec.getApplicationPackageId() == null ? null
                    : packages.findById(exec.getApplicationPackageId()).orElse(null);

            resumeTmp = materialiseResume(pkg);
            String coverLetterText = coverLetterTextOf(pkg);
            UUID sessionId = latestSessionId(exec.getUserId(), exec.getJobId());

            BrowserFormAutomationEngine.DocumentPaths docs = new BrowserFormAutomationEngine.DocumentPaths(
                    resumeTmp,
                    resumeTmp == null ? null : resumeTmp.getFileName().toString(),
                    // Cover letter is deliberately NOT materialised as a file: CoverLetter stores
                    // prose and this platform has no PDF renderer, so writing a .txt and uploading
                    // it where an employer expects a document would be shipping something we know
                    // is wrong. Text fields get the real content; a cover-letter FILE input is
                    // reported as an honest gap instead.
                    null,
                    coverLetterText);

            BrowserFormAutomationEngine.FillOutcome outcome =
                    formEngine.fillForm(exec.getUserId(), sessionId, docs);

            if (!outcome.attempted()) return FormFillReport.notRun();
            log.info("GUEST_APPLY form engine execution={} filled={} skipped={} blocking={}",
                    exec.getId(), outcome.filled().size(), outcome.skipped().size(),
                    outcome.blockingGaps().size());
            return new FormFillReport(true, outcome.success(), outcome.filled(),
                    outcome.skipped(), outcome.blockingGaps());
        } catch (Exception e) {
            log.warn("GUEST_APPLY form engine failed execution={}: {}", exec.getId(), e.toString());
            return FormFillReport.notRun();
        } finally {
            deleteQuietly(resumeTmp);
        }
    }

    /**
     * Downloads the package's resume to a temp file for upload. Returns null when the package has
     * no resume — which the planner then reports as an unresolved (and, on a form that requires a
     * resume, blocking) field rather than uploading nothing and calling it success.
     */
    private Path materialiseResume(ApplicationPackage pkg) {
        if (pkg == null || pkg.getResumeId() == null) return null;
        try {
            Resume resume = resumes.findById(pkg.getResumeId()).orElse(null);
            if (resume == null || resume.getS3Key() == null || resume.getS3Key().isBlank()) return null;
            byte[] bytes = storage.download(resume.getS3Key());
            if (bytes == null || bytes.length == 0) return null;

            // Preserve the real filename: employers and ATS parsers both key off the extension,
            // and a resume uploaded as "tmp1234.bin" is a resume that does not get parsed.
            String filename = resume.getFilename() == null || resume.getFilename().isBlank()
                    ? "resume.pdf" : resume.getFilename();
            Path dir = Files.createTempDirectory("cp-resume-");
            Path file = dir.resolve(filename.replaceAll("[/\\\\]", "_"));
            Files.write(file, bytes);
            return file;
        } catch (Exception e) {
            log.warn("GUEST_APPLY resume materialisation failed package={}: {}", pkg.getId(), e.toString());
            return null;
        }
    }

    private String coverLetterTextOf(ApplicationPackage pkg) {
        if (pkg == null || pkg.getCoverLetterId() == null) return null;
        try {
            return coverLetters.findById(pkg.getCoverLetterId())
                    .map(CoverLetter::getContent).filter(c -> !c.isBlank()).orElse(null);
        } catch (Exception e) {
            log.warn("GUEST_APPLY cover letter lookup failed package={}: {}", pkg.getId(), e.toString());
            return null;
        }
    }

    /**
     * The most recent submission session for this (user, job), which is where generated screening
     * answers live. Reuses an existing finder — no new query. Null when there is no session, in
     * which case screening questions simply resolve unresolved.
     */
    private UUID latestSessionId(UUID userId, UUID jobId) {
        try {
            List<ApplicationSubmissionSession> found =
                    submissionSessions.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId);
            return found.isEmpty() ? null : found.get(0).getId();
        } catch (Exception e) {
            log.warn("GUEST_APPLY submission session lookup failed user={} job={}: {}", userId, jobId, e.toString());
            return null;
        }
    }

    private void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
            Path parent = file.getParent();
            if (parent != null) Files.deleteIfExists(parent);
        } catch (Exception e) {
            log.warn("GUEST_APPLY temp resume cleanup failed: {}", e.toString());
        }
    }

    /** Phase 7.16.3 — best-effort screenshot capture on the FAILURE path; never throws, never blocks the outcome. */
    private void captureFailureScreenshot(ApplicationExecution exec) {
        try {
            captureScreenshot(exec, SCREENSHOT_PHASE_FAILURE);
        } catch (Exception e) {
            log.warn("GUEST_APPLY failure screenshot capture failed execution={}: {}", exec.getId(), e.toString());
        }
    }

    /**
     * Phase 7.16.3 — captures + uploads one screenshot for the given phase, reusing the same
     * storage convention as the existing pre-submit approval screenshot (no new storage layer).
     * Never throws to the caller — a failed capture is logged and otherwise ignored, since it is
     * always ancillary evidence, never load-bearing for the execution's own outcome.
     */
    private void captureScreenshot(ApplicationExecution exec, String phase) {
        try {
            Path tmp = Files.createTempFile("exec-screenshot-", ".png");
            try {
                browser.captureScreenshot(tmp);
                metrics.recordScreenshotCaptured();
                byte[] bytes = Files.readAllBytes(tmp);
                String key = storage.uploadBytes(bytes, "execution-screenshots/" + exec.getId(),
                        phase.toLowerCase(Locale.ROOT) + ".png", "image/png");
                screenshots.save(ExecutionScreenshot.builder()
                        .executionId(exec.getId()).userId(exec.getUserId()).jobId(exec.getJobId())
                        .storageKey(key).phase(phase)
                        .build());
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            metrics.recordScreenshotFailure();
            log.warn("GUEST_APPLY screenshot capture failed execution={} phase={}: {}", exec.getId(), phase, e.toString());
        }
    }

    private void safeLogout() {
        try {
            browser.logout();
        } catch (Exception e) {
            log.warn("GUEST_APPLY browser logout failed: {}", e.toString());
        }
    }

    /**
     * Maps the connector's field SCHEMA (selector -> field type, e.g. "email"/"first_name") to real
     * applicant data. Any field type this method doesn't recognize is left unfilled — values are
     * never fabricated (matches {@link BrowserAutomationProvider#answerQuestions} contract).
     */
    private Map<String, String> resolveFields(Map<String, String> schema, UUID userId) {
        if (schema == null || schema.isEmpty()) return Map.of();
        User user = users.findById(userId).orElse(null);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : schema.entrySet()) {
            String value = realValueFor(field.getValue(), user);
            if (value != null && !value.isBlank()) {
                out.put(field.getKey(), value);
            }
        }
        return out;
    }

    private static String realValueFor(String fieldType, User user) {
        if (user == null || fieldType == null) return null;
        String fullName = user.getFullName();
        return switch (fieldType.toLowerCase(Locale.ROOT)) {
            case "email" -> user.getEmail();
            case "name", "full_name" -> fullName;
            case "first_name" -> firstToken(fullName);
            case "last_name" -> lastToken(fullName);
            default -> null;
        };
    }

    private static String firstToken(String fullName) {
        if (fullName == null || fullName.isBlank()) return null;
        String[] parts = fullName.trim().split("\\s+");
        return parts[0];
    }

    private static String lastToken(String fullName) {
        if (fullName == null || fullName.isBlank()) return null;
        String[] parts = fullName.trim().split("\\s+");
        return parts.length > 1 ? parts[parts.length - 1] : null;
    }

    /** Mirrors {@code JobValidationService#applyUrl} without adding an execution->submission dependency. */
    private static String applyUrl(Job job) {
        if (job == null) return null;
        if (job.getSourceUrl() != null && !job.getSourceUrl().isBlank()) return job.getSourceUrl();
        if (job.getExternalUrl() != null && !job.getExternalUrl().isBlank()) return job.getExternalUrl();
        return null;
    }
}
