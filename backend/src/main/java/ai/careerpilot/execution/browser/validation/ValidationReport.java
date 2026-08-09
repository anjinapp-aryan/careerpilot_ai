package ai.careerpilot.execution.browser.validation;

import ai.careerpilot.execution.browser.form.CanonicalField;
import ai.careerpilot.execution.browser.form.DiscoveredField;
import ai.careerpilot.execution.browser.form.DiscoveryDiagnostics;
import ai.careerpilot.execution.browser.form.FieldControlType;
import ai.careerpilot.execution.browser.form.FrameDiscoveryReport;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 12C.5 — the complete result of validating one employer page. Diagnostics only: no table,
 * no migration, no persistence. It is returned to the caller and the most recent one is held in
 * memory for the browser diagnostics endpoint.
 *
 * <p><b>Nothing in this report is evidence that anything was submitted</b>, and it deliberately
 * does not reuse {@code EvidenceBundle} — every {@code SignalType} in that class means "this
 * submission reached the employer", so filing a validation screenshot there would corrupt the one
 * data structure whose entire job is to be trustworthy about submission outcomes.
 */
public record ValidationReport(
        String url,
        AtsPlatform platform,
        Status status,
        String message,
        Instant startedAt,
        long totalDurationMs,
        long navigationDurationMs,
        long discoveryDurationMs,
        long planningDurationMs,
        List<FieldEntry> fields,
        SelectorCoverage coverage,
        AutomationConfidence confidence,
        PageEnvironment environment,
        String screenshotKey,
        List<String> notes,
        DiscoveryDiagnostics discovery,
        FrameDiscoveryReport frameDiscovery) {

    /**
     * Pre-frame-discovery compatibility constructor. A report built before iframe-aware discovery
     * existed carries an empty {@link FrameDiscoveryReport} rather than a null, so every consumer can
     * read the section unconditionally — same discipline as {@link DiscoveryDiagnostics#empty()}
     * below.
     */
    public ValidationReport(String url, AtsPlatform platform, Status status, String message,
                            Instant startedAt, long totalDurationMs, long navigationDurationMs,
                            long discoveryDurationMs, long planningDurationMs,
                            List<FieldEntry> fields, SelectorCoverage coverage,
                            AutomationConfidence confidence, PageEnvironment environment,
                            String screenshotKey, List<String> notes, DiscoveryDiagnostics discovery) {
        this(url, platform, status, message, startedAt, totalDurationMs, navigationDurationMs,
                discoveryDurationMs, planningDurationMs, fields, coverage, confidence, environment,
                screenshotKey, notes, discovery, FrameDiscoveryReport.empty());
    }

    /**
     * Pre-Phase-B compatibility constructor. A report built without discovery diagnostics — the
     * {@code refused}/{@code failed} paths, where no discovery ever ran — carries an empty one
     * rather than a null, so every consumer can read the section unconditionally.
     */
    public ValidationReport(String url, AtsPlatform platform, Status status, String message,
                            Instant startedAt, long totalDurationMs, long navigationDurationMs,
                            long discoveryDurationMs, long planningDurationMs,
                            List<FieldEntry> fields, SelectorCoverage coverage,
                            AutomationConfidence confidence, PageEnvironment environment,
                            String screenshotKey, List<String> notes) {
        this(url, platform, status, message, startedAt, totalDurationMs, navigationDurationMs,
                discoveryDurationMs, planningDurationMs, fields, coverage, confidence, environment,
                screenshotKey, notes, DiscoveryDiagnostics.empty(), FrameDiscoveryReport.empty());
    }

    public enum Status {
        /**
         * Ran to completion against a page {@link AtsPageVerifier} confirmed is an application form.
         * Says nothing about whether the page is automatable — see confidence.
         */
        COMPLETED,
        /** Refused before opening a browser (URL policy, feature flag). */
        REFUSED,
        /** Started and failed (navigation, discovery, browser). */
        FAILED,

        // ── Phase F4 — verification rejections. Discovery and planning never ran. ──
        // Each is a distinct answer to "why can't you automate this?", which is the entire reason
        // they are separate statuses rather than one INVALID: "the job was deleted", "you need to
        // log in" and "you pasted the careers homepage" call for three different operator actions.

        /** Right posting, page loaded, but no application form is present on it. */
        INVALID_APPLICATION_PAGE,
        /** The posting does not exist: the ATS redirected away from it, or flagged an error. */
        INVALID_POSTING,
        /** The URL left the site entirely and landed somewhere that is not an application. */
        REDIRECTED_TO_NON_APPLICATION,
        /** An authentication wall stands between the URL and any form. Never bypassed. */
        LOGIN_REQUIRED,
        /** The posting existed but is closed, filled, expired, or no longer accepting applications. */
        JOB_REMOVED,
        /** Not a recognised ATS application surface — homepage, search page, maintenance page. */
        UNSUPPORTED_PAGE;

        /** True for every Phase F4 rejection: the page was proven not to be an application form. */
        public boolean isVerificationRejection() {
            return this == INVALID_APPLICATION_PAGE || this == INVALID_POSTING
                    || this == REDIRECTED_TO_NON_APPLICATION || this == LOGIN_REQUIRED
                    || this == JOB_REMOVED || this == UNSUPPORTED_PAGE;
        }

        /** True when discovery and planning were deliberately skipped. */
        public boolean skippedDiscovery() {
            return this == REFUSED || isVerificationRejection();
        }
    }

    public ValidationReport {
        fields = fields == null ? List.of() : List.copyOf(fields);
        notes = notes == null ? List.of() : List.copyOf(notes);
        discovery = discovery == null ? DiscoveryDiagnostics.empty() : discovery;
        frameDiscovery = frameDiscovery == null ? FrameDiscoveryReport.empty() : frameDiscovery;
    }

    /**
     * P0 — the five stages kept deliberately separate, because conflating them is what produced a
     * CAPTCHA-guarded page reporting {@code ready=true}.
     *
     * <p>Each answers a different question, and an earlier one succeeding says nothing about a
     * later one:
     *
     * <ul>
     *   <li><b>DISCOVERY</b> — were controls found on the page?</li>
     *   <li><b>CLASSIFICATION</b> — was it understood what each control is?</li>
     *   <li><b>PLANNING</b> — could a fill plan be produced from verified data?</li>
     *   <li><b>AUTOMATION</b> — <i>can the system actually execute here?</i> This is the only stage
     *       {@code ready} describes. It is {@code BLOCKED} whenever any {@link AutomationBlocker}
     *       exists, however well the three stages above went.</li>
     *   <li><b>SUBMISSION</b> — always {@code NOT_ATTEMPTED} from validation. The harness has no
     *       code path to a submit; this states that in the payload rather than leaving it inferred.</li>
     * </ul>
     */
    public enum PhaseOutcome { SUCCEEDED, SKIPPED, BLOCKED, NOT_ATTEMPTED }

    /** Deterministic per-stage outcome derived from the report itself — never set by hand. */
    public Map<String, Object> phases() {
        boolean skipped = status.skippedDiscovery();
        boolean discovered = !skipped && !fields.isEmpty();
        boolean blocked = confidence != null && confidence.blocked();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("discovery", (skipped ? PhaseOutcome.SKIPPED
                : discovered ? PhaseOutcome.SUCCEEDED : PhaseOutcome.NOT_ATTEMPTED).name());
        out.put("classification", (skipped || !discovered ? PhaseOutcome.SKIPPED
                : PhaseOutcome.SUCCEEDED).name());
        out.put("planning", (skipped || !discovered ? PhaseOutcome.SKIPPED
                : PhaseOutcome.SUCCEEDED).name());
        // The load-bearing line: analysis succeeding never implies automation can run.
        out.put("automation", (skipped ? PhaseOutcome.SKIPPED
                : blocked ? PhaseOutcome.BLOCKED
                : confidence != null && confidence.ready() ? PhaseOutcome.SUCCEEDED
                : PhaseOutcome.BLOCKED).name());
        out.put("submission", PhaseOutcome.NOT_ATTEMPTED.name());
        return out;
    }

    /**
     * One discovered control, with everything that identifies it and what the engine concluded.
     *
     * <p>Values are never included — only the control's identity and classification. This report is
     * read by operators and surfaced on a diagnostics endpoint; a candidate's email address has no
     * business in it.
     */
    public record FieldEntry(
            String cssSelector,
            String xpath,
            String label,
            String placeholder,
            String name,
            String id,
            String ariaLabel,
            String role,
            FieldControlType controlType,
            CanonicalField canonicalField,
            String questionCategory,
            boolean visible,
            boolean required,
            boolean disabled,
            boolean readOnly,
            boolean resolvable,
            String unresolvedReason) {

        public static FieldEntry from(DiscoveredField field, CanonicalField canonical,
                                      String questionCategory, boolean resolvable, String unresolvedReason) {
            return new FieldEntry(
                    field.selector(), field.xpath(), field.label(), field.placeholder(),
                    field.name(), field.id(), field.ariaLabel(), field.role(),
                    field.controlType(), canonical, questionCategory,
                    !field.hidden(), field.required(), field.disabled(), field.readOnly(),
                    resolvable, unresolvedReason);
        }

        public Map<String, Object> snapshot() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cssSelector", cssSelector);
            out.put("xpath", xpath);
            out.put("label", label);
            out.put("placeholder", placeholder);
            out.put("name", name);
            out.put("id", id);
            out.put("ariaLabel", ariaLabel);
            out.put("role", role);
            out.put("controlType", controlType == null ? null : controlType.name());
            out.put("canonicalField", canonicalField == null ? null : canonicalField.name());
            out.put("questionCategory", questionCategory);
            out.put("visible", visible);
            out.put("required", required);
            out.put("disabled", disabled);
            out.put("readOnly", readOnly);
            out.put("resolvable", resolvable);
            out.put("unresolvedReason", unresolvedReason);
            return out;
        }
    }

    /**
     * What the page itself turned out to be. Captured because these are the properties that make a
     * page hard to automate, and knowing them <em>before</em> a failure is the point of this phase.
     *
     * @param spaFramework      detected client framework, or {@code null}
     * @param iframeCount       iframes present — form fields inside one are not reachable by the
     *                          current discovery script, which queries the top document only
     * @param shadowRootCount   open shadow roots, same limitation
     * @param captchaDetected   reported, never solved
     * @param consoleErrorCount JavaScript errors observed after navigation began
     * @param failedRequests    network requests the page itself reported as failed
     */
    public record PageEnvironment(String spaFramework, int iframeCount, int shadowRootCount,
                                  boolean captchaDetected, boolean cookieBannerDetected,
                                  int consoleErrorCount, int failedRequests, String title) {

        public static PageEnvironment unknown() {
            return new PageEnvironment(null, 0, 0, false, false, 0, 0, null);
        }

        public Map<String, Object> snapshot() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("spaFramework", spaFramework);
            out.put("iframeCount", iframeCount);
            out.put("shadowRootCount", shadowRootCount);
            out.put("captchaDetected", captchaDetected);
            out.put("cookieBannerDetected", cookieBannerDetected);
            out.put("consoleErrorCount", consoleErrorCount);
            out.put("failedRequests", failedRequests);
            out.put("title", title);
            return out;
        }
    }

    public static ValidationReport refused(String url, String reason) {
        return new ValidationReport(url, AtsPlatform.detect(url), Status.REFUSED, reason,
                Instant.now(), 0, 0, 0, 0, List.of(), SelectorCoverage.empty(),
                AutomationConfidence.none(reason), PageEnvironment.unknown(), null, List.of());
    }

    public static ValidationReport failed(String url, String reason, Instant startedAt, long durationMs) {
        return new ValidationReport(url, AtsPlatform.detect(url), Status.FAILED, reason,
                startedAt, durationMs, 0, 0, 0, List.of(), SelectorCoverage.empty(),
                AutomationConfidence.none(reason), PageEnvironment.unknown(), null, List.of());
    }

    /**
     * Phase F4 — the page was reached but is not an application form.
     *
     * <p>Confidence is {@link AutomationConfidence#none} carrying the rejection reason, and coverage
     * is {@link SelectorCoverage#empty}, because <b>discovery never ran</b>. That is the point: a
     * score computed from a board index is a misleading number, and the fix is to have no number
     * rather than a wrong one. {@code discoveryDurationMs}/{@code planningDurationMs} are 0 for the
     * same reason, so "skipped" is visible in the timings and not only in the status.
     *
     * @param navigationMs real — navigation genuinely happened, and how long it took is diagnostic
     */
    public static ValidationReport invalidPage(String url, AtsPlatform platform,
                                               AtsPageVerifier.Outcome outcome, Instant startedAt,
                                               long durationMs, long navigationMs,
                                               PageEnvironment environment, String screenshotKey,
                                               List<String> notes) {
        return invalidPage(url, platform, outcome, startedAt, durationMs, navigationMs, environment,
                screenshotKey, notes, FrameDiscoveryReport.empty());
    }

    /**
     * Iframe-aware overload. Frame evidence is carried through even on a rejection — an operator
     * asking "why was this rejected" for a page with an iframe should see {@code framesInspected}/
     * {@code inaccessibleFrames} on the very report that rejected it, not just on a later successful
     * run against a different posting.
     */
    public static ValidationReport invalidPage(String url, AtsPlatform platform,
                                               AtsPageVerifier.Outcome outcome, Instant startedAt,
                                               long durationMs, long navigationMs,
                                               PageEnvironment environment, String screenshotKey,
                                               List<String> notes, FrameDiscoveryReport frameDiscovery) {
        List<String> allNotes = new java.util.ArrayList<>(notes == null ? List.of() : notes);
        allNotes.addAll(outcome.evidence());
        allNotes.add("Discovery and planning were skipped — this page was not an application form, "
                + "so any confidence score computed from it would be misleading.");
        return new ValidationReport(url, platform, outcome.status(), outcome.reason(),
                startedAt, durationMs, navigationMs, 0, 0, List.of(), SelectorCoverage.empty(),
                AutomationConfidence.none(outcome.reason()),
                environment == null ? PageEnvironment.unknown() : environment,
                screenshotKey, allNotes, DiscoveryDiagnostics.empty(),
                frameDiscovery == null ? FrameDiscoveryReport.empty() : frameDiscovery);
    }

    /** Full detail, for the API response. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = summary();
        out.put("fields", fields.stream().map(FieldEntry::snapshot).toList());
        out.put("notes", notes);
        return out;
    }

    /**
     * Compact form for the diagnostics endpoint — everything except the per-field list, which can
     * run to dozens of entries and would dominate a health response.
     */
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", url);
        out.put("atsPlatform", platform == null ? null : platform.name());
        out.put("status", status.name());
        out.put("message", message);
        out.put("startedAt", startedAt == null ? null : startedAt.toString());
        out.put("totalDurationMs", totalDurationMs);
        out.put("navigationDurationMs", navigationDurationMs);
        out.put("discoveryDurationMs", discoveryDurationMs);
        out.put("planningDurationMs", planningDurationMs);
        out.put("selectorsDiscovered", fields.size());
        out.put("discovery", discovery == null
                ? DiscoveryDiagnostics.empty().snapshot() : discovery.snapshot());
        // Iframe-aware discovery evidence — present on every report, including rejections, so
        // "why was this rejected" and "what did frame traversal actually see" are never two
        // different requests. Never invented: an empty FrameDiscoveryReport (the probe wasn't run,
        // or found nothing) reports honest zeros/nulls, not omitted keys.
        out.put("frames", frameDiscovery == null
                ? FrameDiscoveryReport.empty().snapshot() : frameDiscovery.snapshot());
        out.put("coverage", coverage == null ? Map.of() : coverage.snapshot());
        out.put("confidence", confidence == null ? Map.of() : confidence.snapshot());
        out.put("environment", environment == null ? Map.of() : environment.snapshot());
        out.put("screenshotKey", screenshotKey);
        // Phase F4 — stated explicitly so a client never has to infer "was this page even an
        // application form?" from the presence or absence of fields. A rejected page reports
        // pageVerified=false with discovery/planning explicitly skipped.
        out.put("pageVerified", status == Status.COMPLETED);
        out.put("discoverySkipped", status.skippedDiscovery());
        out.put("planningSkipped", status.skippedDiscovery());
        out.put("invalidPosting", status.isVerificationRejection());
        // P0 — the five stages, kept separate so "analysed successfully" can never be read as
        // "executable". automationBlocked is hoisted to the top level because it is the single
        // fact a dashboard must not miss.
        out.put("phases", phases());
        out.put("automationBlocked", confidence != null && confidence.blocked());
        // Stated on every single report so no consumer can mistake this for a submission record.
        out.put("submitted", false);
        out.put("documentsUploaded", false);
        out.put("questionsAnswered", false);
        return out;
    }
}
