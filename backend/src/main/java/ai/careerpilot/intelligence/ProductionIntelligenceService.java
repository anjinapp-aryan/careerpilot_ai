package ai.careerpilot.intelligence;

import ai.careerpilot.domain.ResumeLearning;
import ai.careerpilot.domain.SuccessPattern;
import ai.careerpilot.execution.browser.validation.ValidationHistoryService;
import ai.careerpilot.repo.ResumeLearningRepository;
import ai.careerpilot.repo.SuccessPatternRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Phase 13B — the orchestration layer. <b>It aggregates; it does not analyse.</b>
 *
 * <p>Every number this class emits was computed by a service that already existed:
 * {@code ResumeLearningService}/{@code ResumePerformanceAnalyzer} produced the resume rates,
 * {@code SuccessPatternEngine}'s 20+ analyzers produced the dimension patterns, and Phase 13A's
 * {@code ValidationHistoryService} produced the ATS readiness. Nothing here recomputes a rate, and
 * no competing analyzer was introduced — that was the phase's central constraint.
 *
 * <h2>What it deliberately leaves to CareerContextService</h2>
 * Mission, Career Timeline, Career Memory, Workflow, Applications, Interviews and Company Knowledge
 * are <b>not</b> aggregated here. {@code CareerContextService} (Phase 11A) already does all of it
 * and is already wired into the Copilot. Restating them would create two sources that can disagree
 * about the same fact — the precise duplication the stop conditions forbid. The two compose at the
 * Copilot: one describes what is happening, this one describes what the evidence says works.
 *
 * <h2>Reads, not writes</h2>
 * This service never triggers a recompute. {@code ResumeLearningService.recompute} and the pattern
 * workers are driven by their own existing event pipeline; calling them from a read path would turn
 * a Copilot question into a write transaction. If the learning tables are stale, the snapshot says
 * when they were last computed rather than silently refreshing them.
 *
 * <p>Per-source try/catch isolation throughout ({@code safely}), mirroring
 * {@code CareerContextService}'s established convention: one missing or disabled subsystem degrades
 * its own section, never the whole snapshot.
 *
 * <p>Gated by {@code production.intelligence.enabled} (default {@code false}).
 */
@Service
public class ProductionIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(ProductionIntelligenceService.class);

    /** How many entries per dimension to surface. Beyond this a "top list" stops being a signal. */
    private static final int TOP_N = 5;

    private final ResumeLearningRepository resumeLearning;
    private final SuccessPatternRepository successPatterns;
    private final ObjectProvider<ValidationHistoryService> validationHistory;
    private final boolean enabled;

    public ProductionIntelligenceService(ResumeLearningRepository resumeLearning,
                                         SuccessPatternRepository successPatterns,
                                         ObjectProvider<ValidationHistoryService> validationHistory,
                                         @Value("${production.intelligence.enabled:false}") boolean enabled) {
        this.resumeLearning = resumeLearning;
        this.successPatterns = successPatterns;
        this.validationHistory = validationHistory;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Build one snapshot. Never throws.
     *
     * <p><b>Query budget: one per dimension, four in total</b> — resume learning, and three
     * {@code SuccessPattern} reads (location, company, skill), plus the ATS readiness read which is
     * itself bounded. No N+1: each repository call returns the full ordered list for that dimension
     * and everything after it is in-memory truncation.
     */
    public ProductionOptimizationSnapshot getSnapshot(UUID userId) {
        if (!enabled) {
            return ProductionOptimizationSnapshot.empty(
                    "production intelligence is disabled (production.intelligence.enabled=false)");
        }
        if (userId == null) {
            return ProductionOptimizationSnapshot.empty("no user supplied");
        }

        List<String> notes = new ArrayList<>();
        ProductionOptimizationSnapshot.ResumeIntelligence resume = safely(() -> resumeIntelligence(userId), notes, "resume");
        List<ProductionOptimizationSnapshot.DimensionFinding> countries =
                safelyList(() -> dimension(userId, SuccessPattern.DIM_LOCATION), notes, "countries");
        List<ProductionOptimizationSnapshot.DimensionFinding> companies =
                safelyList(() -> dimension(userId, SuccessPattern.DIM_COMPANY), notes, "companies");
        List<ProductionOptimizationSnapshot.DimensionFinding> skills =
                safelyList(() -> dimension(userId, SuccessPattern.DIM_SKILL), notes, "skills");
        ProductionOptimizationSnapshot.AtsIntelligence ats = safely(this::atsIntelligence, notes, "ats");

        ProductionOptimizationSnapshot snapshot = new ProductionOptimizationSnapshot(
                Instant.now(), resume, countries, companies, skills, ats, notes);

        if (snapshot.isEmpty()) {
            // The honest empty state. A new user has no production evidence, and that is a fact
            // about their history, not a failure of this service.
            return new ProductionOptimizationSnapshot(snapshot.generatedAt(), null, List.of(), List.of(),
                    List.of(), null, List.of("No verified production evidence available yet."));
        }
        return snapshot;
    }

    // ── sources ──

    /**
     * The best resume version, read straight from the {@code resume_learning} rows
     * {@code ResumeLearningService} already computed.
     *
     * <p>Prefers that service's own {@code bestVersion} flag over re-ranking here: it decided the
     * tie-break rule (highest offer rate, then interview rate) and re-implementing it would be a
     * second definition of "best" that can drift from the first.
     */
    private ProductionOptimizationSnapshot.ResumeIntelligence resumeIntelligence(UUID userId) {
        List<ResumeLearning> versions = resumeLearning.findByUserIdOrderByOfferRateDesc(userId);
        if (versions.isEmpty()) return null;

        ResumeLearning best = versions.stream().filter(ResumeLearning::isBestVersion).findFirst()
                .orElse(versions.get(0));
        int applications = best.getApplications();

        Evidence evidence = Evidence.of("ResumeLearningService", applications,
                orderedCounts(best.getInterviews(), best.getOffers()), best.getComputedAt());
        if (!evidence.isActionable()) {
            // Enough to report, not enough to recommend. The reason says so in the same breath as
            // the numbers, so a reader is never left to infer why nothing is being suggested.
            return new ProductionOptimizationSnapshot.ResumeIntelligence(
                    null, applications, best.getInterviews(), best.getOffers(),
                    asDouble(best.getInterviewRate()), asDouble(best.getOfferRate()), versions.size(),
                    evidence,
                    "Only " + applications + " application(s) recorded for the leading version — below the "
                            + Evidence.MINIMUM_SAMPLE + "-observation floor, so no version is recommended yet.");
        }
        return new ProductionOptimizationSnapshot.ResumeIntelligence(
                best.getResumeVersion(), applications, best.getInterviews(), best.getOffers(),
                asDouble(best.getInterviewRate()), asDouble(best.getOfferRate()), versions.size(),
                evidence,
                "Highest offer rate across " + versions.size() + " version(s) with recorded outcomes.");
    }

    /**
     * Top entries within one learned dimension, from the {@code success_pattern} rows the Phase 6
     * analyzers already produced.
     *
     * <p>Entries below the evidence floor are dropped rather than shown greyed-out: a "top country"
     * list whose first row rests on two applications teaches the reader to distrust the list.
     */
    private List<ProductionOptimizationSnapshot.DimensionFinding> dimension(UUID userId, String dimension) {
        List<SuccessPattern> patterns =
                successPatterns.findByUserIdAndDimensionOrderBySuccessRateDesc(userId, dimension);
        List<ProductionOptimizationSnapshot.DimensionFinding> out = new ArrayList<>();
        for (SuccessPattern pattern : patterns) {
            if (out.size() >= TOP_N) break;
            Evidence evidence = Evidence.of("SuccessPatternEngine[" + dimension + "]",
                    pattern.getApplications(),
                    orderedCounts(pattern.getInterviews(), pattern.getOffers()),
                    pattern.getComputedAt());
            if (!evidence.isActionable()) continue;
            out.add(new ProductionOptimizationSnapshot.DimensionFinding(
                    dimension, pattern.getDimensionKey(), pattern.getApplications(),
                    pattern.getInterviews(), pattern.getOffers(),
                    asDouble(pattern.getSuccessRate()), evidence));
        }
        return out;
    }

    /**
     * ATS automation readiness, from Phase 13A's validation history.
     *
     * <p>Carries an explicit caveat because the obvious misreading is costly: this ranks how
     * reliably we can <em>fill</em> each ATS's forms, not which ATS produces more interviews. No
     * table in this platform records outcomes keyed by ATS, so the second claim cannot be made and
     * is not implied.
     */
    private ProductionOptimizationSnapshot.AtsIntelligence atsIntelligence() {
        ValidationHistoryService history = validationHistory.getIfAvailable();
        if (history == null || !history.isEnabled()) return null;

        List<ValidationHistoryService.PlatformReadiness> readiness = history.platformReadiness();
        if (readiness.isEmpty()) return null;

        List<ValidationHistoryService.PlatformReadiness> ranked = readiness.stream()
                .sorted((a, b) -> Integer.compare(b.averageConfidence(), a.averageConfidence()))
                .toList();
        ValidationHistoryService.PlatformReadiness best = ranked.get(0);
        ValidationHistoryService.PlatformReadiness weakest = ranked.get(ranked.size() - 1);

        int totalRuns = readiness.stream().mapToInt(ValidationHistoryService.PlatformReadiness::completedRuns).sum();
        Evidence evidence = Evidence.of("ValidationHistoryService", totalRuns,
                Map.of("platforms validated", readiness.size()), best.lastValidatedAt());

        return new ProductionOptimizationSnapshot.AtsIntelligence(
                best.platform(), best.averageConfidence(),
                ranked.size() > 1 ? weakest.platform() : null,
                ranked.size() > 1 ? weakest.averageConfidence() : null,
                readiness.stream().filter(ValidationHistoryService.PlatformReadiness::ready)
                        .map(ValidationHistoryService.PlatformReadiness::platform).toList(),
                evidence,
                "Measures form-automation readiness only. This platform records no outcome data keyed "
                        + "by ATS, so it does not indicate which ATS produces more interviews.");
    }

    // ── helpers ──

    /** Preserves interviews-then-offers ordering in the citation, which reads as a funnel. */
    private static Map<String, Integer> orderedCounts(int interviews, int offers) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("interviews", interviews);
        counts.put("offers", offers);
        return counts;
    }

    private <T> T safely(Supplier<T> supplier, List<String> notes, String section) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("PRODUCTION_INTELLIGENCE section '{}' unavailable: {}", section, e.toString());
            notes.add(section + " unavailable: " + e);
            return null;
        }
    }

    private <T> List<T> safelyList(Supplier<List<T>> supplier, List<String> notes, String section) {
        List<T> value = safely(supplier, notes, section);
        return value == null ? List.of() : value;
    }

    private static Double asDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
