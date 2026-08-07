package ai.careerpilot.execution.browser.validation;

import ai.careerpilot.execution.browser.CaptchaLoginDetector;
import ai.careerpilot.execution.browser.PlaywrightAutomationProvider;
import ai.careerpilot.execution.browser.form.CanonicalField;
import ai.careerpilot.execution.browser.form.DiscoveredField;
import ai.careerpilot.execution.browser.form.DiscoveryDiagnostics;
import ai.careerpilot.execution.browser.form.FieldClassifier;
import ai.careerpilot.execution.browser.form.FormControlReducer;
import ai.careerpilot.execution.browser.form.FormDiscoveryScript;
import ai.careerpilot.execution.browser.form.FormFillPlan;
import ai.careerpilot.execution.browser.form.FormFillPlanner;
import ai.careerpilot.storage.S3StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 12C.5 — opens a real employer application page, discovers and classifies every control,
 * produces an automation plan and a confidence score, captures evidence, and <b>stops</b>.
 *
 * <h2>What this class must never do</h2>
 * It never uploads a file, never answers a question, never clicks submit, and never advances a
 * multi-step form. That is not a configuration choice: {@link FormFillPlanner} is called to
 * <em>plan</em>, and {@code BrowserFormAutomationEngine} — the only thing that writes to a page —
 * <b>is not a dependency of this class at all</b>. There is no code path from here to an
 * interaction, so the guarantee holds by construction rather than by discipline.
 *
 * <p>The one thing it does write is a screenshot to the platform's own storage, which touches no
 * employer system.
 *
 * <h2>Documents are declared, not loaded</h2>
 * Planning needs to know <em>whether</em> a resume exists to decide if a required file input is a
 * blocking gap. It does not need the bytes. The caller passes availability flags, so no resume is
 * ever materialised on disk during validation — a file that is never fetched cannot be uploaded by
 * mistake.
 *
 * <p>Gated by {@code browser.validation.enabled} (default {@code false}) <b>and</b> the master
 * {@code browser.automation.enabled}, since it needs a browser at all.
 */
@Service
public class BrowserValidationHarness {

    private static final Logger log = LoggerFactory.getLogger(BrowserValidationHarness.class);

    private final PlaywrightAutomationProvider browser;
    private final FieldClassifier classifier;
    private final FormFillPlanner planner;
    private final ValidationUrlPolicy urlPolicy;
    private final BrowserValidationMetrics metrics;
    private final S3StorageService storage;

    private final boolean enabled;
    private final boolean automationEnabled;
    private final long settleMs;
    private final boolean captureScreenshot;

    public BrowserValidationHarness(PlaywrightAutomationProvider browser,
                                    FieldClassifier classifier,
                                    FormFillPlanner planner,
                                    ValidationUrlPolicy urlPolicy,
                                    BrowserValidationMetrics metrics,
                                    S3StorageService storage,
                                    @Value("${browser.validation.enabled:false}") boolean enabled,
                                    @Value("${browser.automation.enabled:false}") boolean automationEnabled,
                                    @Value("${browser.validation.settle-ms:1500}") long settleMs,
                                    @Value("${browser.validation.capture-screenshot:true}") boolean captureScreenshot) {
        this.browser = browser;
        this.classifier = classifier;
        this.planner = planner;
        this.urlPolicy = urlPolicy;
        this.metrics = metrics;
        this.storage = storage;
        this.enabled = enabled;
        this.automationEnabled = automationEnabled;
        this.settleMs = Math.max(0, settleMs);
        this.captureScreenshot = captureScreenshot;
    }

    public boolean isEnabled() {
        return enabled && automationEnabled;
    }

    /**
     * Availability of the documents an application would need. Flags only — see the class note on
     * why the bytes are deliberately not loaded here.
     */
    public record DocumentAvailability(boolean resume, boolean coverLetter) {
        public static DocumentAvailability none() {
            return new DocumentAvailability(false, false);
        }
    }

    /**
     * Validate one page. Never throws — every failure becomes a {@link ValidationReport} with a
     * status and a reason, because a diagnostic that can explode is not a diagnostic.
     *
     * @param userId    whose verified data the plan resolves against
     * @param sessionId optional submission session holding generated screening answers
     */
    public ValidationReport validate(String url, UUID userId, UUID sessionId, DocumentAvailability documents) {
        if (!enabled) {
            return ValidationReport.refused(url, "browser validation is disabled (browser.validation.enabled=false)");
        }
        if (!automationEnabled) {
            return ValidationReport.refused(url, "browser automation is disabled (browser.automation.enabled=false)");
        }

        ValidationUrlPolicy.Verdict verdict = urlPolicy.evaluate(url);
        if (!verdict.allowed()) {
            metrics.recordRefused();
            log.warn("BROWSER_VALIDATION refused url: {}", verdict.reason());
            return ValidationReport.refused(url, verdict.reason());
        }

        Instant startedAt = Instant.now();
        long start = System.currentTimeMillis();
        metrics.recordAttempt();
        DocumentAvailability docs = documents == null ? DocumentAvailability.none() : documents;
        List<String> notes = new ArrayList<>();

        try {
            // Opens a pooled lease. Everything below runs inside it; the finally block always
            // releases, so a validation run can never leak the single production browser permit.
            long navStart = System.currentTimeMillis();
            browser.navigate(url);
            browser.startConsoleCapture();
            browser.waitForStable(settleMs);
            long navigationMs = System.currentTimeMillis() - navStart;

            // Reported, never solved — solving a CAPTCHA would be circumventing an access control
            // the employer deliberately placed. Validation continues so the report can still show
            // what (if anything) was discoverable behind it.
            boolean captchaOrLogin = false;
            try {
                captchaOrLogin = CaptchaLoginDetector.looksLikeCaptchaOrLogin(browser.currentPageHtml());
            } catch (Exception e) {
                notes.add("page HTML unavailable for CAPTCHA detection: " + e);
            }
            if (captchaOrLogin) {
                notes.add("CAPTCHA or login wall detected — reported, never solved. "
                        + "Discovery results below may be from the interstitial rather than the form.");
            }

            // ── Phase F4 — prove this is an application form BEFORE discovery runs against it ──
            //
            // The bug: https://boards.greenhouse.io/gitlab/jobs/12345 is not a real posting, but
            // Greenhouse answers it with HTTP 200 after redirecting to the company's board index.
            // Navigation succeeded, so discovery ran on an index page and the harness reported
            // COMPLETED with a confidence score. A score derived from a page that was never an
            // application form is worse than no score, because an operator will act on it.
            //
            // This runs before discovery, not after, so a rejected page produces no coverage and no
            // confidence at all rather than numbers that would have to be explained away.
            AtsPageVerifier.PageIdentity identity = null;
            try {
                identity = FormDiscoveryScript.parsePageIdentity(
                        browser.evaluate(FormDiscoveryScript.DISCOVER_PAGE_IDENTITY), url);
            } catch (Exception e) {
                notes.add("page identity probe failed, proceeding to discovery: " + e);
            }
            AtsPageVerifier.Outcome verification = AtsPageVerifier.verify(identity, verdict.platform());
            if (!verification.valid()) {
                // The screenshot is still captured: "what did it actually load?" is exactly the
                // question an operator asks about a rejection, and answering it costs one image.
                ValidationReport.PageEnvironment rejectedEnv = environment(notes, List.of());
                String rejectedShot = captureScreenshot ? captureEvidence(url, notes) : null;
                long rejectedMs = System.currentTimeMillis() - start;
                ValidationReport report = ValidationReport.invalidPage(url, verdict.platform(),
                        verification, startedAt, rejectedMs, navigationMs, rejectedEnv,
                        rejectedShot, notes);
                metrics.recordInvalidPage(report);
                log.warn("BROWSER_VALIDATION rejected url={} finalUrl={} status={} reason={}",
                        url, identity == null ? "(unknown)" : identity.finalUrl(),
                        verification.status(), verification.reason());
                return report;
            }
            notes.add("Page verified as an application form (signal score "
                    + verification.signalScore() + "/5).");

            long discoveryStart = System.currentTimeMillis();
            Object rawDiscovery = browser.evaluate(FormDiscoveryScript.DISCOVER_FIELDS);
            // Phase B — reduction runs here rather than in the page, so the rule that decides a
            // control is a framework phantom is plain Java and directly unit-testable.
            FormControlReducer.Reduction reduction = FormControlReducer.reduce(
                    FormDiscoveryScript.parse(rawDiscovery),
                    FormDiscoveryScript.parseRawCounters(rawDiscovery));
            List<DiscoveredField> discovered = reduction.fields();
            long discoveryMs = System.currentTimeMillis() - discoveryStart;
            DiscoveryDiagnostics discovery = reduction.diagnostics().withDuration(discoveryMs);
            notes.addAll(discovery.notes());

            ValidationReport.PageEnvironment environment = environment(notes, discovered);

            long planStart = System.currentTimeMillis();
            List<CanonicalField> classified = classifier.classifyAll(discovered);
            FormFillPlan plan = planner.plan(discovered, userId, sessionId,
                    new FormFillPlanner.Documents(docs.resume(), docs.coverLetter(), null));
            long planningMs = System.currentTimeMillis() - planStart;

            SelectorCoverage coverage = SelectorCoverage.from(discovered, classified, plan);
            // P0 — what stops execution, computed separately from how well the page was analysed.
            List<AutomationBlocker> blockers = blockers(captchaOrLogin, environment, coverage, discovered);
            AutomationConfidence confidence = AutomationConfidence.from(coverage, blockers);
            if (!blockers.isEmpty()) {
                notes.add("AUTOMATION BLOCKED: " + blockers.stream()
                        .map(b -> b.reason().name() + " (" + b.detail() + ")")
                        .collect(java.util.stream.Collectors.joining("; ")));
                metrics.recordBlocked(blockers);
            }
            List<ValidationReport.FieldEntry> entries = fieldEntries(discovered, classified, plan);
            String screenshotKey = captureScreenshot ? captureEvidence(url, notes) : null;

            long totalMs = System.currentTimeMillis() - start;
            ValidationReport report = new ValidationReport(url, verdict.platform(),
                    ValidationReport.Status.COMPLETED,
                    "validation completed — nothing was uploaded, answered or submitted",
                    startedAt, totalMs, navigationMs, discoveryMs, planningMs,
                    entries, coverage, confidence, environment, screenshotKey, notes, discovery);

            metrics.recordCompleted(report);
            log.info("BROWSER_VALIDATION completed url={} ats={} controls={} confidence={}% band={} ready={}",
                    url, verdict.platform(), coverage.totalControls(),
                    confidence.score(), confidence.band(), confidence.ready());
            return report;

        } catch (Exception e) {
            long totalMs = System.currentTimeMillis() - start;
            metrics.recordFailed();
            log.warn("BROWSER_VALIDATION failed url={}: {}", url, e.toString());
            ValidationReport report = ValidationReport.failed(url, e.toString(), startedAt, totalMs);
            metrics.recordLast(report);
            return report;
        } finally {
            // Always. A validation run must never hold the browser permit that real submissions need.
            try {
                browser.logout();
            } catch (Exception e) {
                log.warn("BROWSER_VALIDATION lease release failed: {}", e.toString());
            }
            // The console buffer is drained on the success path only. A run that threw earlier —
            // navigation failure, page-identity probe failure — otherwise left page-derived text
            // bound to this pooled request thread indefinitely.
            try {
                browser.clearConsoleCapture();
            } catch (Exception e) {
                log.warn("BROWSER_VALIDATION console buffer cleanup failed: {}", e.toString());
            }
        }
    }

    /**
     * P0 — everything that makes execution impossible, derived only from evidence already gathered.
     *
     * <p>Nothing here is new detection: CAPTCHA/login come from the existing {@code
     * CaptchaLoginDetector} and environment probe, missing required data from the plan the coverage
     * already summarises, cross-origin frames from the environment counts. The defect was never
     * that these facts were unknown — they were all on the report already — but that none of them
     * reached the readiness verdict.
     *
     * <p>Order is severity-first so {@code blockedReason} names the most fundamental obstacle: a
     * page behind a CAPTCHA cannot be executed even if every field were fillable.
     */
    private static List<AutomationBlocker> blockers(boolean captchaOrLogin,
                                                    ValidationReport.PageEnvironment environment,
                                                    SelectorCoverage coverage,
                                                    List<DiscoveredField> discovered) {
        List<AutomationBlocker> out = new ArrayList<>();

        if (environment != null && environment.captchaDetected()) {
            out.add(AutomationBlocker.of(AutomationBlocker.Reason.CAPTCHA,
                    "a CAPTCHA provider is present in the page; this platform detects and reports "
                            + "CAPTCHAs and never solves them, so a submission cannot complete here"));
        } else if (captchaOrLogin) {
            // The HTML detector fires on either; without a provider script in the environment probe
            // the more likely explanation is a sign-in wall.
            out.add(AutomationBlocker.of(AutomationBlocker.Reason.LOGIN_WALL,
                    "a CAPTCHA or sign-in wall was detected in the page HTML"));
        }

        if (discovered.isEmpty()) {
            int iframes = environment == null ? 0 : environment.iframeCount();
            out.add(iframes > 0
                    ? AutomationBlocker.of(AutomationBlocker.Reason.CROSS_ORIGIN_FRAME,
                            "no controls in the top document, but " + iframes
                                    + " iframe(s) are present — the form is most likely inside one, "
                                    + "which the browser prevents reading across")
                    : AutomationBlocker.of(AutomationBlocker.Reason.NO_FORM));
        } else if (coverage.missingRequiredValues() > 0) {
            out.add(AutomationBlocker.of(AutomationBlocker.Reason.MISSING_REQUIRED_DATA,
                    coverage.missingRequiredValues() + " required field(s) have no verified value; "
                            + "values are never fabricated, so the form cannot be completed"));
        }
        return out;
    }

    private ValidationReport.PageEnvironment environment(List<String> notes, List<DiscoveredField> discovered) {
        try {
            List<String> consoleErrors = browser.drainConsoleErrors();
            ValidationReport.PageEnvironment env = FormDiscoveryScript.parseEnvironment(
                    browser.evaluate(FormDiscoveryScript.DISCOVER_ENVIRONMENT), consoleErrors.size());
            if (!consoleErrors.isEmpty()) {
                notes.add("JavaScript errors observed after navigation: " + String.join(" | ", consoleErrors));
            }
            // The diagnosis that would otherwise take an engineer an hour to reach: the discovery
            // script queries the top-level document only, so a form inside an iframe or shadow root
            // is invisible to it. Zero fields plus a non-zero iframe count is a very different
            // problem from a genuinely empty page.
            if (discovered.isEmpty() && (env.iframeCount() > 0 || env.shadowRootCount() > 0)) {
                notes.add("No controls found at the top level, but the page has "
                        + env.iframeCount() + " iframe(s) and " + env.shadowRootCount()
                        + " shadow root(s) — the form is most likely inside one, which discovery "
                        + "does not currently traverse.");
            }
            if (env.captchaDetected()) {
                notes.add("CAPTCHA provider detected in the page. Detection only — never solved.");
            }
            return env;
        } catch (Exception e) {
            notes.add("page environment probe failed: " + e);
            return ValidationReport.PageEnvironment.unknown();
        }
    }

    /**
     * One report row per discovered control. Resolvability is read back from the plan rather than
     * recomputed, so the report cannot drift from the decision it is describing.
     */
    private List<ValidationReport.FieldEntry> fieldEntries(List<DiscoveredField> discovered,
                                                           List<CanonicalField> classified,
                                                           FormFillPlan plan) {
        Map<String, String> unresolvedBySelector = new HashMap<>();
        for (FormFillPlan.UnresolvedField u : plan.unresolved()) {
            unresolvedBySelector.put(u.field().selector(), u.reason());
        }
        java.util.Set<String> resolvedSelectors = new java.util.HashSet<>();
        for (FormFillPlan.PlannedFill f : plan.fills()) {
            resolvedSelectors.add(f.field().selector());
        }

        List<ValidationReport.FieldEntry> entries = new ArrayList<>();
        for (int i = 0; i < discovered.size(); i++) {
            DiscoveredField field = discovered.get(i);
            CanonicalField canonical = i < classified.size() ? classified.get(i) : CanonicalField.UNKNOWN;
            boolean resolvable = resolvedSelectors.contains(field.selector());
            String reason = resolvable ? null
                    : unresolvedBySelector.getOrDefault(field.selector(),
                            field.isFillable() ? "not planned" : "control is not fillable");
            String category = canonical == CanonicalField.SCREENING_QUESTION
                    ? String.valueOf(classifier.questionCategoryOf(field)) : null;
            entries.add(ValidationReport.FieldEntry.from(field, canonical, category, resolvable, reason));
        }
        return entries;
    }

    /**
     * Screenshots the page into the platform's own storage, reusing the same convention as the
     * execution screenshots. Best-effort: a failed capture is a note on the report, never a failed
     * validation — the plan and coverage numbers are the deliverable, the image is corroboration.
     */
    private String captureEvidence(String url, List<String> notes) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("validation-", ".png");
            browser.captureScreenshot(tmp);
            byte[] bytes = Files.readAllBytes(tmp);
            String key = storage.uploadBytes(bytes,
                    "browser-validation/" + AtsPlatform.detect(url).name().toLowerCase(java.util.Locale.ROOT),
                    UUID.randomUUID() + ".png", "image/png");
            metrics.recordScreenshot();
            return key;
        } catch (Exception e) {
            notes.add("screenshot capture failed: " + e);
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception e) {
                    log.warn("BROWSER_VALIDATION temp screenshot cleanup failed: {}", e.toString());
                }
            }
        }
    }
}
