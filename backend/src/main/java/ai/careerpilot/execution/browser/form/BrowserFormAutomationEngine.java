package ai.careerpilot.execution.browser.form;

import ai.careerpilot.execution.browser.PlaywrightAutomationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 12C — the single, vendor-neutral engine that fills a real employer application form.
 *
 * <p>There is deliberately no {@code GreenhouseUploader}/{@code LeverUploader}. Nothing in this
 * class, or anywhere in this package, names an ATS. Portability comes from working off what the
 * live DOM reports (discovery) and what this platform can verify (resolution), so a form it has
 * never seen is handled the same way as one it has.
 *
 * <p><b>It never decides what an answer should be.</b> By the time execution starts, the complete
 * decision already exists as a {@link FormFillPlan} — inspectable, loggable, assertable — and this
 * class only carries it out. That split is what makes "no fabricated answers" checkable rather than
 * merely asserted.
 *
 * <p><b>It never submits.</b> {@link #fillForm} stops at the last filled field. Reaching a real
 * submit stays exactly where it already was: behind {@code GuestApplyAutomationService}'s
 * screenshot capture and the mandatory human {@code FORM_SCREENSHOT} approval. This engine widens
 * what can be filled, not what can be sent.
 *
 * <p>Gated by {@code browser.automation.form-engine.enabled} (default {@code false}). With the flag
 * off, {@link #fillForm} returns an explicit disabled result without touching the page, so the
 * pre-Phase-12C behaviour is byte-identical.
 */
@Service
public class BrowserFormAutomationEngine {

    private static final Logger log = LoggerFactory.getLogger(BrowserFormAutomationEngine.class);

    private final PlaywrightAutomationProvider browser;
    private final FormFillPlanner planner;
    private final ValidationErrorDetector validationDetector;
    private final MultiStepFormNavigator navigator;
    private final FormAutomationMetrics metrics;
    private final boolean enabled;
    private final boolean requireUploadVerification;

    public BrowserFormAutomationEngine(
            PlaywrightAutomationProvider browser,
            FormFillPlanner planner,
            ValidationErrorDetector validationDetector,
            MultiStepFormNavigator navigator,
            FormAutomationMetrics metrics,
            @Value("${browser.automation.form-engine.enabled:false}") boolean enabled,
            @Value("${browser.automation.form-engine.require-upload-verification:true}") boolean requireUploadVerification) {
        this.browser = browser;
        this.planner = planner;
        this.validationDetector = validationDetector;
        this.navigator = navigator;
        this.metrics = metrics;
        this.enabled = enabled;
        this.requireUploadVerification = requireUploadVerification;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Files resolved from the {@code ApplicationPackage} by the caller. Either may be null. */
    public record DocumentPaths(Path resume, String resumeFileName, Path coverLetter, String coverLetterText) {
        public static DocumentPaths none() {
            return new DocumentPaths(null, null, null, null);
        }
    }

    /**
     * The outcome of one fill attempt. Never an exception — the caller
     * ({@code GuestApplyAutomationService}) already owns the abort/approve decision, and a fill
     * engine that throws would bypass its screenshot-and-park path.
     *
     * @param filled       fields actually written and, where checkable, confirmed
     * @param skipped      fields deliberately left alone, each with a reason
     * @param blockingGaps required fields with no verified value — non-empty means DO NOT submit
     */
    public record FillOutcome(boolean attempted, boolean success, String reason,
                              List<String> filled, Map<String, String> skipped,
                              List<String> blockingGaps, Map<String, Object> evidence) {

        public static FillOutcome disabled() {
            return new FillOutcome(false, false, "form engine disabled",
                    List.of(), Map.of(), List.of(), Map.of());
        }

        public static FillOutcome failed(String reason, Map<String, Object> evidence) {
            return new FillOutcome(true, false, reason, List.of(), Map.of(), List.of(), evidence);
        }

        /** True when the form is complete enough that a human may be asked to approve it. */
        public boolean isApprovable() {
            return success && blockingGaps.isEmpty();
        }
    }

    /**
     * Discover, plan, and fill the form on the currently-open page.
     *
     * <p>Assumes the caller has already navigated and cleared the CAPTCHA/login-wall check — this
     * engine never navigates, so it can never be pointed at a different page than the one the
     * safety checks ran against.
     */
    public FillOutcome fillForm(UUID userId, UUID sessionId, DocumentPaths documents) {
        if (!enabled) return FillOutcome.disabled();

        long start = System.currentTimeMillis();
        metrics.recordAttempt();
        Map<String, Object> evidence = new LinkedHashMap<>();
        DocumentPaths docs = documents == null ? DocumentPaths.none() : documents;

        try {
            int dismissed = browser.dismissOverlays();
            if (dismissed > 0) evidence.put("overlaysDismissed", dismissed);

            List<DiscoveredField> discovered = FormDiscoveryScript.parse(
                    browser.evaluate(FormDiscoveryScript.DISCOVER_FIELDS));
            evidence.put("fieldsDiscovered", discovered.size());
            if (discovered.isEmpty()) {
                metrics.recordFailure();
                return FillOutcome.failed("no form fields discovered on the page", evidence);
            }

            FormFillPlanner.Documents planDocs = new FormFillPlanner.Documents(
                    docs.resume() != null, docs.coverLetter() != null, docs.coverLetterText());
            FormFillPlan plan = planner.plan(discovered, userId, sessionId, planDocs);
            evidence.put("plan", plan.summary());

            List<String> filled = new ArrayList<>();
            Map<String, String> skipped = new LinkedHashMap<>();
            for (FormFillPlan.UnresolvedField u : plan.unresolved()) {
                skipped.put(u.field().displayName(), u.reason());
            }

            for (FormFillPlan.PlannedFill fill : plan.fills()) {
                String outcome = applyFill(fill, docs);
                if (outcome == null) {
                    filled.add(fill.canonical().name());
                    metrics.recordFieldFilled();
                } else {
                    // A field that could not be written is a skip with a reason, never a silent
                    // success. If it was required, it becomes a blocking gap below.
                    skipped.put(fill.field().displayName(), outcome);
                    metrics.recordFieldFailed();
                }
            }

            List<String> blocking = new ArrayList<>(plan.blockingGaps().stream()
                    .map(g -> g.field().displayName() + ": " + g.reason())
                    .toList());
            for (FormFillPlan.PlannedFill fill : plan.fills()) {
                if (fill.field().required() && skipped.containsKey(fill.field().displayName())) {
                    blocking.add(fill.field().displayName() + ": " + skipped.get(fill.field().displayName()));
                }
            }

            // Inline validation the form raised while we were typing — captured before any submit,
            // so a rejected field is known now rather than after an irreversible click.
            ValidationErrorDetector.PostSubmitState state = FormDiscoveryScript.parseValidationState(
                    browser.evaluate(FormDiscoveryScript.DISCOVER_VALIDATION_STATE), false);
            List<ValidationErrorDetector.ValidationError> errors = validationDetector.detect(state);
            if (!errors.isEmpty()) {
                evidence.put("validationErrors", errors.stream().map(ValidationErrorDetector.ValidationError::message).toList());
                metrics.recordValidationError();
            }

            evidence.put("filledCount", filled.size());
            evidence.put("skippedCount", skipped.size());
            evidence.put("durationMs", System.currentTimeMillis() - start);

            boolean success = !filled.isEmpty();
            if (success) metrics.recordSuccess(); else metrics.recordFailure();
            log.info("FORM_ENGINE user={} discovered={} filled={} skipped={} blocking={}",
                    userId, discovered.size(), filled.size(), skipped.size(), blocking.size());

            return new FillOutcome(true, success,
                    success ? null : "no field could be filled from verified data",
                    List.copyOf(filled), Map.copyOf(skipped), List.copyOf(blocking), evidence);

        } catch (Exception e) {
            metrics.recordFailure();
            log.warn("FORM_ENGINE fill failed user={}: {}", userId, e.toString());
            evidence.put("error", e.toString());
            evidence.put("durationMs", System.currentTimeMillis() - start);
            return FillOutcome.failed(e.toString(), evidence);
        }
    }

    /**
     * Write one planned value. Returns {@code null} on success, or the reason it was skipped.
     *
     * <p>File uploads are <b>verified by reading the input back</b>. A {@code setInputFiles} that
     * silently no-ops — a detached element after an AJAX re-render is the common cause — would
     * otherwise be reported as a successful upload, and the employer would receive an application
     * with no resume attached. That is exactly the class of fabricated success this phase forbids.
     */
    private String applyFill(FormFillPlan.PlannedFill fill, DocumentPaths docs) {
        DiscoveredField field = fill.field();
        String selector = field.selector();
        try {
            FormFillPlanner.DocumentSlot slot = FormFillPlanner.DocumentSlot.fromToken(fill.value());
            if (slot != null) {
                Path file = slot == FormFillPlanner.DocumentSlot.RESUME ? docs.resume() : docs.coverLetter();
                if (file == null) return "document not available at execution time";
                browser.uploadFileTo(selector, file);
                if (requireUploadVerification && !browser.hasUploadedFile(selector)) {
                    metrics.recordUploadFailure();
                    return "upload could not be verified — the input reports no attached file";
                }
                metrics.recordUpload();
                return null;
            }

            switch (field.controlType()) {
                case SELECT -> browser.selectOptionAt(selector, fill.value());
                case CHECKBOX, RADIO -> {
                    Boolean checked = FormFillPlanner.asBoolean(fill.value());
                    if (checked == null) return "value is not a yes/no answer for a checkbox/radio";
                    browser.setCheckedAt(selector, checked);
                }
                case RICH_TEXT -> browser.fillRichText(selector, fill.value());
                case FILE -> {
                    return "file input reached without a resolved document";
                }
                case UNSUPPORTED -> {
                    return "control type is not supported by this engine";
                }
                default -> browser.fillAt(selector, fill.value());
            }
            return null;
        } catch (Exception e) {
            return "interaction failed: " + e;
        }
    }

    /**
     * Read the page's clickable controls and decide the next navigation step.
     *
     * <p>Exposed but <b>not called by any submitting path</b>. Multi-step traversal means clicking
     * through pages of a live employer form, and the current approval gate screenshots exactly one
     * page — so wiring this in would let a human approve page 1 while pages 2-4 were filled
     * unseen. Closing that gap is a deliberate follow-up, not something to slip in here.
     */
    public MultiStepFormNavigator.Decision nextStep() {
        if (!enabled) return MultiStepFormNavigator.Decision.unclear("form engine disabled");
        try {
            return navigator.decide(FormDiscoveryScript.parseButtons(
                    browser.evaluate(FormDiscoveryScript.DISCOVER_BUTTONS)));
        } catch (Exception e) {
            return MultiStepFormNavigator.Decision.unclear("button discovery failed: " + e);
        }
    }
}
