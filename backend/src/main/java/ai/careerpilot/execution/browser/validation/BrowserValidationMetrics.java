package ai.careerpilot.execution.browser.validation;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 12C.5 — validation counters, plus the most recent report per ATS so the diagnostics
 * endpoint can serve a compatibility picture without a table.
 *
 * <p>Everything here is <b>in memory and deliberately not persisted</b>: this is diagnostic
 * scaffolding for an operator deciding whether to advance a rollout, not a business record. It
 * costs no migration and disappears on restart, which is the correct lifetime for a measurement of
 * "how automatable is this ATS today".
 *
 * <p>Bounded by construction — one entry per {@link AtsPlatform} enum value, so the map cannot grow
 * with traffic no matter how many URLs are validated.
 */
@Component
public class BrowserValidationMetrics {

    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private final AtomicLong screenshots = new AtomicLong();
    /** Phase F4 — reached the page, but it was not an application form. */
    private final AtomicLong invalidPages = new AtomicLong();
    /** P0 — analysed fine, but execution is impossible. Counted per reason, not just in total. */
    private final AtomicLong blockedPages = new AtomicLong();
    private final Map<AutomationBlocker.Reason, AtomicLong> blockedByReason = new ConcurrentHashMap<>();

    private final AtomicReference<ValidationReport> lastReport = new AtomicReference<>();
    private final Map<AtsPlatform, ValidationReport> latestByPlatform = new ConcurrentHashMap<>();

    public void recordAttempt() { attempts.incrementAndGet(); }
    public void recordFailed() { failed.incrementAndGet(); }
    public void recordRefused() { refused.incrementAndGet(); }
    public void recordScreenshot() { screenshots.incrementAndGet(); }

    public void recordCompleted(ValidationReport report) {
        completed.incrementAndGet();
        recordLast(report);
        if (report != null && report.platform() != null) {
            latestByPlatform.put(report.platform(), report);
        }
    }

    /**
     * Phase F4 — the page was reached but proven not to be an application form.
     *
     * <p>Counted separately from both {@code completed} and {@code failed}, and — critically —
     * <b>never written into {@code latestByPlatform}</b>. The compatibility report answers "how
     * automatable is this ATS", and a fake posting says nothing about Greenhouse's forms. Filing a
     * rejection there would drop that platform's readiness to zero on the strength of a typo'd URL.
     */
    public void recordInvalidPage(ValidationReport report) {
        invalidPages.incrementAndGet();
        recordLast(report);
    }

    /** P0 — a page that was analysed successfully but cannot be executed. */
    public void recordBlocked(java.util.List<AutomationBlocker> blockers) {
        if (blockers == null || blockers.isEmpty()) return;
        blockedPages.incrementAndGet();
        for (AutomationBlocker b : blockers) {
            blockedByReason.computeIfAbsent(b.reason(), k -> new AtomicLong()).incrementAndGet();
        }
    }

    /** Also called on failure, so "last validation" reflects what actually happened last. */
    public void recordLast(ValidationReport report) {
        if (report != null) lastReport.set(report);
    }

    public ValidationReport lastReport() {
        return lastReport.get();
    }

    /**
     * The ATS compatibility report: the most recent validation per platform, as
     * confidence/coverage/readiness. Deliberately <b>last</b>, not an average — an average across
     * different job postings on the same ATS blends a simple 6-field form with a 40-field one and
     * produces a number describing neither.
     */
    public Map<String, Object> compatibilityReport() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<AtsPlatform, ValidationReport> entry : latestByPlatform.entrySet()) {
            ValidationReport report = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            // P0 — an ATS whose pages are all CAPTCHA-guarded must not read as "94% confident".
            // The score describes analysis quality; these two describe whether anything can run.
            row.put("automationBlocked", report.confidence().blocked());
            row.put("blockedReason", report.confidence().blockers().isEmpty() ? null
                    : report.confidence().blockers().get(0).reason().name());
            row.put("automationConfidence", report.confidence().score());
            row.put("confidenceMeaning", "analysis completeness only — see automationBlocked for "
                    + "whether execution is actually possible");
            row.put("band", report.confidence().band().name());
            row.put("ready", report.confidence().ready());
            row.put("selectorsSupported", report.coverage().supportedControls());
            row.put("unsupported", report.coverage().unsupportedControls());
            row.put("unknown", report.coverage().unknownControls());
            row.put("requiredMissing", report.coverage().missingRequiredValues());
            row.put("validatedAt", report.startedAt() == null ? null : report.startedAt().toString());
            // One posting is one data point. Saying so stops a single green result being read as
            // "this ATS is solved".
            row.put("basis", "most recent validation only — not an average across postings");
            out.put(entry.getKey().name(), row);
        }
        return out;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("validationAttempts", attempts.get());
        out.put("validationCompleted", completed.get());
        out.put("validationFailed", failed.get());
        out.put("validationRefused", refused.get());
        out.put("validationInvalidPages", invalidPages.get());
        out.put("validationBlockedPages", blockedPages.get());
        Map<String, Object> byReason = new LinkedHashMap<>();
        blockedByReason.forEach((k, v) -> byReason.put(k.name(), v.get()));
        out.put("validationBlockedByReason", byReason);
        out.put("validationScreenshots", screenshots.get());
        ValidationReport last = lastReport.get();
        out.put("lastValidation", last == null ? null : last.summary());
        out.put("atsCompatibility", compatibilityReport());
        return out;
    }
}
