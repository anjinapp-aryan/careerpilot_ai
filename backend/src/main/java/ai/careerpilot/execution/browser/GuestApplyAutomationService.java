package ai.careerpilot.execution.browser;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.ApprovalQueueEntry;
import ai.careerpilot.domain.ExecutionScreenshot;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.User;
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

    public GuestApplyAutomationService(PlaywrightAutomationProvider browser, BrowserAutomationMetrics metrics,
                                       S3StorageService storage, ApprovalService approvalService,
                                       ExecutionScreenshotRepository screenshots, UserRepository users,
                                       @Value("${browser.automation.guest-apply-only:true}") boolean guestApplyOnlyFlag) {
        this.browser = browser;
        this.metrics = metrics;
        this.storage = storage;
        this.approvalService = approvalService;
        this.screenshots = screenshots;
        this.users = users;
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

            Path tmp = Files.createTempFile("exec-screenshot-", ".png");
            try {
                browser.captureScreenshot(tmp);
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
            String reference = connector.submit(job, Map.of());
            metrics.recordRealSubmission();
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
            metrics.recordLatency(System.currentTimeMillis() - start);
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
