package ai.careerpilot.execution.browser.validation;

import ai.careerpilot.execution.browser.form.CanonicalField;
import ai.careerpilot.execution.browser.form.DiscoveredField;
import ai.careerpilot.execution.browser.form.DiscoveryDiagnostics;
import ai.careerpilot.execution.browser.form.FieldControlType;

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
        DiscoveryDiagnostics discovery) {

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
                screenshotKey, notes, DiscoveryDiagnostics.empty());
    }

    public enum Status {
        /** Ran to completion. Says nothing about whether the page is automatable — see confidence. */
        COMPLETED,
        /** Refused before opening a browser (URL policy, feature flag). */
        REFUSED,
        /** Started and failed (navigation, discovery, browser). */
        FAILED
    }

    public ValidationReport {
        fields = fields == null ? List.of() : List.copyOf(fields);
        notes = notes == null ? List.of() : List.copyOf(notes);
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
        out.put("coverage", coverage == null ? Map.of() : coverage.snapshot());
        out.put("confidence", confidence == null ? Map.of() : confidence.snapshot());
        out.put("environment", environment == null ? Map.of() : environment.snapshot());
        out.put("screenshotKey", screenshotKey);
        // Stated on every single report so no consumer can mistake this for a submission record.
        out.put("submitted", false);
        out.put("documentsUploaded", false);
        out.put("questionsAnswered", false);
        return out;
    }
}
