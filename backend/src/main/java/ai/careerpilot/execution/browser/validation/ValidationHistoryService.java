package ai.careerpilot.execution.browser.validation;

import ai.careerpilot.domain.AtsValidationRun;
import ai.careerpilot.repo.AtsValidationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 13A — persists every validation run and answers the questions history makes possible:
 * how many pages of an ATS have been tested, whether confidence is trending, and whether today's
 * run drifted from that posting's own baseline.
 *
 * <p>Phase 12C.5 kept the latest result per ATS in memory. That answered "is this automatable right
 * now" and could answer nothing about change over time, which is what a validation <em>campaign</em>
 * needs. This service is that gap and only that gap — the harness, the confidence engine and the
 * coverage engine are untouched.
 *
 * <p><b>Append-only.</b> {@link #record} inserts; nothing here updates or deletes. Never throws:
 * a persistence failure must not fail the validation run that produced the result, because the
 * report is the deliverable and the row is the bookkeeping.
 *
 * <p><b>PII boundary.</b> {@link #campaignReport()} is served on the unauthenticated diagnostics
 * endpoint and emits aggregates only — never a URL, never a user id. Per-posting detail is
 * reachable only through the authenticated path.
 */
@Service
public class ValidationHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ValidationHistoryService.class);

    private final AtsValidationRunRepository runs;
    private final SelectorDriftDetector driftDetector;
    private final boolean enabled;

    public ValidationHistoryService(AtsValidationRunRepository runs,
                                    SelectorDriftDetector driftDetector,
                                    @Value("${browser.validation.history.enabled:false}") boolean enabled) {
        this.runs = runs;
        this.driftDetector = driftDetector;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Persist one run and evaluate it against that posting's baseline.
     *
     * <p>Returns the drift verdict so the caller can surface it immediately — an operator running a
     * validation should learn about a regression in that response, not by checking a dashboard
     * later.
     */
    public SelectorDriftDetector.DriftReport record(ValidationReport report, UUID userId) {
        if (!enabled || report == null) {
            return new SelectorDriftDetector.DriftReport(
                    SelectorDriftDetector.Severity.NO_BASELINE,
                    List.of("validation history is disabled (browser.validation.history.enabled=false)"),
                    null, null, 0);
        }
        try {
            String urlHash = hash(report.url());
            // Read the baseline BEFORE inserting, so the current run cannot be its own baseline.
            List<AtsValidationRun> prior = runs.findTop20ByUrlHashOrderByCreatedAtDesc(urlHash);

            AtsValidationRun row = toRow(report, userId, urlHash);
            SelectorDriftDetector.DriftReport drift = driftDetector.detect(row, prior);

            runs.save(row);

            if (drift.isAlerting()) {
                // WARN, not INFO. A silent confidence drop is precisely what this phase exists to
                // prevent, and an operator has to be able to find it in the log.
                log.warn("ATS_VALIDATION_DRIFT {} platform={} severity={} baseline={} current={} reasons={}",
                        report.url(), report.platform(), drift.severity(),
                        drift.baselineConfidence(), drift.currentConfidence(), drift.reasons());
            }
            return drift;
        } catch (Exception e) {
            log.warn("ATS_VALIDATION history record failed: {}", e.toString());
            return new SelectorDriftDetector.DriftReport(
                    SelectorDriftDetector.Severity.NO_BASELINE,
                    List.of("history unavailable: " + e), null, null, 0);
        }
    }

    /**
     * The ATS Validation Dashboard: one row per platform with pages tested, average confidence,
     * control quality, readiness, last validation and the trend against the older half of the
     * series.
     *
     * <p>Aggregates only — safe for the unauthenticated diagnostics endpoint.
     */
    public Map<String, Object> campaignReport() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled) {
            out.put("enabled", false);
            // The distinction matters: a disabled feature and an empty campaign look identical
            // otherwise, and one of them is a configuration mistake.
            out.put("note", "validation history is disabled — no verified data available");
            return out;
        }
        out.put("enabled", true);
        // One query for "which platforms have history", then one series query per platform that
        // actually does. Previously this looped every AtsPlatform enum value, so nine of the
        // eleven round-trips returned nothing on a real deployment — 3.9s per call on an endpoint
        // that requires no authentication. The output is unchanged: a platform missing from the
        // distinct list is one whose series would have been empty and skipped below.
        Set<String> tested;
        try {
            tested = new LinkedHashSet<>(runs.findDistinctAtsPlatforms());
        } catch (Exception e) {
            log.warn("VALIDATION_HISTORY platform enumeration failed: {}", e.toString());
            tested = Set.of();
        }
        Map<String, Object> platforms = new LinkedHashMap<>();
        for (AtsPlatform platform : AtsPlatform.values()) {
            if (!tested.contains(platform.name())) continue;   // never invent a row for an untested ATS
            try {
                List<AtsValidationRun> series = runs.findTop50ByAtsPlatformOrderByCreatedAtDesc(platform.name());
                if (series.isEmpty()) continue;
                platforms.put(platform.name(), platformSummary(platform, series));
            } catch (Exception e) {
                platforms.put(platform.name(), Map.of("unavailable", true, "error", String.valueOf(e)));
            }
        }
        out.put("platforms", platforms);
        if (platforms.isEmpty()) {
            out.put("note", "No verified validation data available.");
        }
        return out;
    }

    private Map<String, Object> platformSummary(AtsPlatform platform, List<AtsValidationRun> series) {
        List<AtsValidationRun> completed = series.stream()
                .filter(r -> ValidationReport.Status.COMPLETED.name().equals(r.getStatus()))
                .toList();

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pagesTested", series.size());
        // Distinct postings, not runs: validating the same page ten times is one page of coverage.
        row.put("distinctPostings", series.stream().map(AtsValidationRun::getUrlHash).distinct().count());
        row.put("completedRuns", completed.size());
        row.put("failedRuns", series.size() - completed.size());

        if (completed.isEmpty()) {
            row.put("note", "No completed runs — no verified confidence data available.");
            return row;
        }

        row.put("averageConfidence", (int) Math.round(
                completed.stream().mapToInt(r -> nz(r.getConfidenceScore())).average().orElse(0)));
        row.put("readyRuns", completed.stream().filter(r -> Boolean.TRUE.equals(r.getReady())).count());
        row.put("ready", completed.get(0).getReady());
        row.put("unknownControlRate", rate(completed, AtsValidationRun::getUnknownControls));
        row.put("unsupportedControlRate", rate(completed, AtsValidationRun::getUnsupportedControls));
        row.put("avgTotalMs", (long) completed.stream().mapToLong(r -> nzl(r.getTotalMs())).average().orElse(0));
        row.put("lastValidation", completed.get(0).getCreatedAt() == null
                ? null : completed.get(0).getCreatedAt().toString());
        row.put("trend", trend(completed));
        return row;
    }

    /**
     * Newer half vs. older half of the series. Deliberately not a regression line: with a handful
     * of runs a fitted slope reads as precision this data does not have, and "improving by 0.7
     * points per run" would be a fabricated level of confidence.
     */
    private Map<String, Object> trend(List<AtsValidationRun> completedNewestFirst) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (completedNewestFirst.size() < 4) {
            out.put("direction", "INSUFFICIENT_DATA");
            out.put("note", "at least 4 completed runs are needed before a trend can be stated");
            return out;
        }
        int half = completedNewestFirst.size() / 2;
        double recent = completedNewestFirst.subList(0, half).stream()
                .mapToInt(r -> nz(r.getConfidenceScore())).average().orElse(0);
        double older = completedNewestFirst.subList(half, completedNewestFirst.size()).stream()
                .mapToInt(r -> nz(r.getConfidenceScore())).average().orElse(0);
        int delta = (int) Math.round(recent - older);

        out.put("recentAverage", (int) Math.round(recent));
        out.put("previousAverage", (int) Math.round(older));
        out.put("delta", delta);
        // A 3-point band around flat: below that, the movement is noise from differently-sized
        // postings, not a trend anyone should act on.
        out.put("direction", delta >= 3 ? "IMPROVING" : delta <= -3 ? "DEGRADING" : "STABLE");
        return out;
    }

    /**
     * Phase 13B — per-platform automation readiness, typed.
     *
     * <p>Added so {@code ProductionIntelligenceService} can consume this without either re-deriving
     * the averages (duplicate computation) or parsing {@link #campaignReport()}'s display map
     * (fragile). This service owns validation history, so the typed read belongs here.
     *
     * @param completedRuns how many completed runs back the figure — the orchestrator turns this
     *                      into an {@code Evidence} sample size rather than inventing one
     */
    public record PlatformReadiness(String platform, int averageConfidence, boolean ready,
                                    int completedRuns, java.time.Instant lastValidatedAt) {}

    public List<PlatformReadiness> platformReadiness() {
        if (!enabled) return List.of();
        List<PlatformReadiness> out = new java.util.ArrayList<>();
        for (AtsPlatform platform : AtsPlatform.values()) {
            try {
                List<AtsValidationRun> completed = runs
                        .findTop50ByAtsPlatformOrderByCreatedAtDesc(platform.name()).stream()
                        .filter(r -> ValidationReport.Status.COMPLETED.name().equals(r.getStatus()))
                        .toList();
                if (completed.isEmpty()) continue;   // never invent a row for an untested ATS
                int average = (int) Math.round(completed.stream()
                        .mapToInt(r -> nz(r.getConfidenceScore())).average().orElse(0));
                out.add(new PlatformReadiness(platform.name(), average,
                        Boolean.TRUE.equals(completed.get(0).getReady()),
                        completed.size(), completed.get(0).getCreatedAt()));
            } catch (Exception e) {
                log.warn("ATS_VALIDATION readiness lookup failed platform={}: {}", platform, e.toString());
            }
        }
        return List.copyOf(out);
    }

    /** Recent history for one posting, authenticated callers only (it contains the URL). */
    public List<AtsValidationRun> historyFor(String url) {
        if (!enabled || url == null) return List.of();
        try {
            return runs.findTop20ByUrlHashOrderByCreatedAtDesc(hash(url));
        } catch (Exception e) {
            log.warn("ATS_VALIDATION history lookup failed: {}", e.toString());
            return List.of();
        }
    }

    private static AtsValidationRun toRow(ValidationReport report, UUID userId, String urlHash) {
        SelectorCoverage coverage = report.coverage() == null ? SelectorCoverage.empty() : report.coverage();
        AutomationConfidence confidence = report.confidence() == null
                ? AutomationConfidence.none("no confidence computed") : report.confidence();
        ValidationReport.PageEnvironment env = report.environment() == null
                ? ValidationReport.PageEnvironment.unknown() : report.environment();

        return AtsValidationRun.builder()
                .userId(userId)
                .atsPlatform(report.platform() == null ? AtsPlatform.UNKNOWN.name() : report.platform().name())
                .url(report.url() == null ? "" : report.url())
                .urlHash(urlHash)
                .status(report.status().name())
                .message(report.message())
                .confidenceScore(confidence.score())
                .confidenceBand(confidence.band().name())
                .ready(confidence.ready())
                .totalControls(coverage.totalControls())
                .fillableControls(coverage.fillableControls())
                .supportedControls(coverage.supportedControls())
                .unsupportedControls(coverage.unsupportedControls())
                .unknownControls(coverage.unknownControls())
                .requiredControls(coverage.requiredControls())
                .mappedControls(coverage.mappedControls())
                .missingRequiredValues(coverage.missingRequiredValues())
                .navigationMs(report.navigationDurationMs())
                .discoveryMs(report.discoveryDurationMs())
                .planningMs(report.planningDurationMs())
                .totalMs(report.totalDurationMs())
                .spaFramework(env.spaFramework())
                .iframeCount(env.iframeCount())
                .shadowRootCount(env.shadowRootCount())
                .captchaDetected(env.captchaDetected())
                .consoleErrorCount(env.consoleErrorCount())
                .screenshotKey(report.screenshotKey())
                .build();
    }

    /**
     * SHA-256 of the URL. Used as the per-posting series key so the index does not carry a long,
     * user-identifying string, and so two callers validating the same posting share a baseline.
     */
    static String hash(String url) {
        String normalised = url == null ? "" : url.trim();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalised.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // Cannot happen for SHA-256; degrade to a stable non-crypto key rather than failing.
            return Integer.toHexString(normalised.hashCode());
        }
    }

    private static Object rate(List<AtsValidationRun> runs, java.util.function.Function<AtsValidationRun, Integer> f) {
        long controls = runs.stream().mapToLong(r -> nz(r.getTotalControls())).sum();
        if (controls == 0) return null;   // no denominator — report nothing rather than 0%
        long counted = runs.stream().mapToLong(r -> nz(f.apply(r))).sum();
        return Math.round((counted * 1000.0) / controls) / 10.0;   // one decimal place, as a percentage
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long nzl(Long v) {
        return v == null ? 0L : v;
    }
}
