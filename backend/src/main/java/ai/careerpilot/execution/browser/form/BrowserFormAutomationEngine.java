package ai.careerpilot.execution.browser.form;

import ai.careerpilot.execution.browser.PlaywrightAutomationProvider;
import ai.careerpilot.execution.timeline.ExecutionStage;
import ai.careerpilot.execution.timeline.ExecutionTimelineRecorder;
import ai.careerpilot.execution.timeline.FailureCategory;
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
    /**
     * P7 Action 4 — observability only; every method on it is total (see its own javadoc), so this
     * dependency can never change whether or how a form is filled. Always the real bean: it is the
     * per-call {@link ExecutionTimelineRecorder.RunContext}, not this reference, that is null for
     * the pre-existing 3-arg {@link #fillForm(UUID, UUID, DocumentPaths)} overload — the recorder's
     * own null-run handling then makes every stage call a no-op, so that overload (used by {@code
     * attemptFill} and {@code MultiStepExecutionOrchestrator}) is byte-identical to before this
     * action.
     */
    private final ExecutionTimelineRecorder timeline;

    public BrowserFormAutomationEngine(
            PlaywrightAutomationProvider browser,
            FormFillPlanner planner,
            ValidationErrorDetector validationDetector,
            MultiStepFormNavigator navigator,
            FormAutomationMetrics metrics,
            ExecutionTimelineRecorder timeline,
            @Value("${browser.automation.form-engine.enabled:false}") boolean enabled,
            @Value("${browser.automation.form-engine.require-upload-verification:true}") boolean requireUploadVerification) {
        this.browser = browser;
        this.planner = planner;
        this.validationDetector = validationDetector;
        this.navigator = navigator;
        this.metrics = metrics;
        this.timeline = timeline;
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
        return fillForm(userId, sessionId, documents, null);
    }

    /**
     * P7 Action 4 — same as {@link #fillForm(UUID, UUID, DocumentPaths)}, additionally instrumenting
     * the engine's three real internal seams (discovery, resolution, document upload) on the P5
     * execution timeline. {@code run} is null for the pre-existing overload above — {@link
     * ExecutionTimelineRecorder} already treats a null/unusable run as a no-op at every call, so
     * that overload's behaviour is unchanged by this addition. This overload is currently called
     * only from {@code GuestApplyAutomationService.finalizeSubmit} — the pre-click, pre-approval
     * {@code attemptFill} path and {@code MultiStepExecutionOrchestrator} keep using the 3-arg form,
     * a deliberate scope decision (see the P7 Action 4 gate report), not an oversight.
     */
    public FillOutcome fillForm(UUID userId, UUID sessionId, DocumentPaths documents,
                                ExecutionTimelineRecorder.RunContext run) {
        if (!enabled) return FillOutcome.disabled();

        long start = System.currentTimeMillis();
        metrics.recordAttempt();
        Map<String, Object> evidence = new LinkedHashMap<>();
        DocumentPaths docs = documents == null ? DocumentPaths.none() : documents;

        try {
            int dismissed = browser.dismissOverlays();
            if (dismissed > 0) evidence.put("overlaysDismissed", dismissed);

            UUID discoveryStage = timeline.started(run, ExecutionStage.FORM_DISCOVERY_STARTED);
            List<DiscoveredField> discovered = FormDiscoveryScript.parse(
                    browser.evaluate(FormDiscoveryScript.DISCOVER_FIELDS));
            evidence.put("fieldsDiscovered", discovered.size());
            if (discovered.isEmpty()) {
                metrics.recordFailure();
                timeline.failed(discoveryStage, FailureCategory.QUESTION_PARSING,
                        "no form fields discovered on the page");
                return FillOutcome.failed("no form fields discovered on the page", evidence);
            }
            timeline.completed(discoveryStage, Map.of("fieldsDiscovered", discovered.size()));
            timeline.mark(run, ExecutionStage.FORM_DISCOVERED, Map.of("fieldsDiscovered", discovered.size()));

            // Questions Extracted is a real, distinct checkpoint even though it shares the discovery
            // call's timing: it marks the moment the raw DOM signals become the counted question set
            // the planner will operate on. No separate browser round-trip backs it, so it is a mark
            // (instant), never a started/completed pair with its own duration.
            long requiredCount = discovered.stream().filter(DiscoveredField::required).count();
            timeline.mark(run, ExecutionStage.QUESTIONS_EXTRACTED, Map.of(
                    "questionCount", discovered.size(), "requiredQuestionCount", requiredCount));

            FormFillPlanner.Documents planDocs = new FormFillPlanner.Documents(
                    docs.resume() != null, docs.coverLetter() != null, docs.coverLetterText());
            UUID resolveStage = timeline.started(run, ExecutionStage.QUESTIONS_RESOLVED);
            FormFillPlan plan = planner.plan(discovered, userId, sessionId, planDocs);
            evidence.put("plan", plan.summary());
            timeline.completed(resolveStage, Map.of(
                    "resolvedCount", plan.fills().size(),
                    "unresolvedCount", plan.unresolved().size(),
                    "blockedCount", plan.blockingGaps().size()));

            List<String> filled = new ArrayList<>();
            Map<String, String> skipped = new LinkedHashMap<>();
            for (FormFillPlan.UnresolvedField u : plan.unresolved()) {
                skipped.put(u.field().displayName(), u.reason());
            }

            // Document upload has no separable loop of its own — uploads and ordinary fills share
            // the same fill loop below. Counting the planned uploads up front, rather than
            // restructuring the loop, keeps this purely additive: the fill order and applyFill
            // logic are byte-for-byte unchanged.
            long plannedUploads = plan.fills().stream()
                    .filter(f -> FormFillPlanner.DocumentSlot.fromToken(f.value()) != null)
                    .count();
            UUID uploadStage = plannedUploads > 0
                    ? timeline.started(run, ExecutionStage.DOCUMENT_UPLOAD_STARTED,
                            Map.of("plannedUploads", plannedUploads))
                    : null;

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

            if (uploadStage != null) {
                long uploadsFailed = plan.fills().stream()
                        .filter(f -> FormFillPlanner.DocumentSlot.fromToken(f.value()) != null)
                        .filter(f -> skipped.containsKey(f.field().displayName()))
                        .count();
                if (uploadsFailed > 0) {
                    timeline.failed(uploadStage, FailureCategory.UPLOAD,
                            uploadsFailed + " of " + plannedUploads + " planned upload(s) failed");
                } else {
                    timeline.completed(uploadStage, Map.of("uploadsCompleted", plannedUploads));
                    // Matching the FORM_DISCOVERY_STARTED/FORM_DISCOVERED convention: the started/
                    // completed pair closes the timed row, and this instant mark is the distinct
                    // DOCUMENT_UPLOAD_COMPLETED stage a reader can query for directly, rather than
                    // needing to know DOCUMENT_UPLOAD_STARTED's row can itself carry COMPLETED status.
                    timeline.mark(run, ExecutionStage.DOCUMENT_UPLOAD_COMPLETED,
                            Map.of("uploadsCompleted", plannedUploads));
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
                case COMBOBOX -> {
                    return applyComboboxFill(selector, fill.value());
                }
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
     * P7 Action 5C-FIX — the fill strategy for {@link FieldControlType#COMBOBOX}: click to open,
     * live-read the now-rendered options, match the verified answer against them, click the match,
     * then read the control's own displayed text back to confirm the click actually took effect.
     *
     * <p>Deliberately not implemented with coordinates or a blind keyboard sequence — see
     * {@code FormDiscoveryScript.SELECT_COMBOBOX_OPTION}'s javadoc for the full interaction and
     * verification contract. Returns {@code null} on a verified success, or the reason otherwise;
     * same contract as every other branch of {@link #applyFill}.
     */
    private String applyComboboxFill(String selector, String expectedValue) {
        Map<String, Object> args = Map.of("selector", selector, "expected",
                expectedValue == null ? "" : expectedValue);
        FormDiscoveryScript.ComboboxSelectionResult result = FormDiscoveryScript.parseComboboxSelection(
                browser.evaluate(FormDiscoveryScript.SELECT_COMBOBOX_OPTION, args));
        if (!result.matched()) {
            return "combobox: " + (result.reason() == null
                    ? "no option matched the verified answer" : result.reason());
        }
        if (!result.verified()) {
            return "combobox: " + (result.reason() == null
                    ? "selection could not be verified in the DOM after click" : result.reason());
        }
        return null;
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
