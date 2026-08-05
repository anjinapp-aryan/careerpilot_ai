package ai.careerpilot.intelligence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 13B — converts a {@link ProductionOptimizationSnapshot} into ranked, evidence-cited
 * recommendations. Pure, deterministic, no LLM and no repository access — the same discipline as
 * {@code RecommendedActionEngine} (11B), {@code RetryPolicyService} and {@code AutomationConfidence}.
 *
 * <h2>Why it cannot fabricate</h2>
 * It has nothing to fabricate <em>from</em>. Every input arrived already carrying an
 * {@link Evidence} record, and {@code ProductionIntelligenceService} dropped everything below the
 * evidence floor before this service ever saw it. A recommendation without a citation is therefore
 * not something this class chooses to avoid — it is something it structurally cannot construct.
 *
 * <h2>Comparative claims</h2>
 * Statements of the form "Germany converts better than the Netherlands" are only made when the
 * snapshot genuinely holds both, and the margin is quoted from their two real rates. With one
 * entry in a dimension the recommendation is stated flatly, with no comparison invented to make it
 * sound stronger — which is the tempting error here, because "+21%" reads far better than "based on
 * 31 applications".
 *
 * <p>Gated by {@code optimization.recommendations.enabled} (default {@code false}).
 */
@Service
public class OptimizationRecommendationService {

    /**
     * A difference smaller than this between two rates is not worth acting on — it is the same
     * dead-band reasoning as {@code ValidationHistoryService}'s trend direction, and it stops the
     * engine telling someone to change strategy over a one-point wobble.
     */
    private static final double MATERIAL_MARGIN = 5.0;

    private final ProductionIntelligenceService intelligence;
    private final boolean enabled;

    public OptimizationRecommendationService(ProductionIntelligenceService intelligence,
                                             @Value("${optimization.recommendations.enabled:false}") boolean enabled) {
        this.intelligence = intelligence;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * One actionable recommendation.
     *
     * @param priority lower is more important; the ordering is the ladder in {@link #recommend}
     * @param action   what to do, in the imperative
     * @param rationale why, in terms of what was measured — never "this seems better"
     * @param evidence the citation; never {@code null}
     */
    public record Recommendation(int priority, String category, String action,
                                 String rationale, Evidence evidence) {

        public Map<String, Object> snapshot() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("priority", priority);
            out.put("category", category);
            out.put("action", action);
            out.put("rationale", rationale);
            out.put("evidence", evidence.snapshot());
            return out;
        }

        /** One line, ready to render in a Copilot answer or a dashboard row. */
        public String render() {
            return action + " — " + rationale + " [" + evidence.cite() + "]";
        }
    }

    /** Convenience overload: build the snapshot and recommend from it. */
    public List<Recommendation> recommend(UUID userId) {
        if (!enabled) return List.of();
        return recommend(intelligence.getSnapshot(userId));
    }

    /**
     * The ladder. Ordering reflects how directly each lever changes an outcome: the resume goes on
     * every application, so it outranks a geography choice, which outranks a per-company one.
     */
    public List<Recommendation> recommend(ProductionOptimizationSnapshot snapshot) {
        if (!enabled || snapshot == null || snapshot.isEmpty()) return List.of();
        List<Recommendation> out = new ArrayList<>();

        resumeRecommendation(snapshot).ifPresent(out::add);
        dimensionRecommendation(snapshot.countries(), 2, "COUNTRY",
                key -> "Prioritise applications in " + key).ifPresent(out::add);
        dimensionRecommendation(snapshot.skills(), 3, "SKILL",
                key -> "Invest in " + key).ifPresent(out::add);
        dimensionRecommendation(snapshot.companies(), 4, "COMPANY",
                key -> "Prioritise applications to " + key).ifPresent(out::add);
        atsRecommendation(snapshot).ifPresent(out::add);

        return List.copyOf(out);
    }

    // ── rules ──

    private java.util.Optional<Recommendation> resumeRecommendation(ProductionOptimizationSnapshot snapshot) {
        ProductionOptimizationSnapshot.ResumeIntelligence resume = snapshot.resume();
        // A null recommendedVersion means the snapshot itself judged the evidence too thin. It is
        // not this service's place to overrule that.
        if (resume == null || resume.recommendedVersion() == null) return java.util.Optional.empty();

        StringBuilder rationale = new StringBuilder();
        rationale.append("it produced ").append(resume.interviews()).append(" interview")
                .append(resume.interviews() == 1 ? "" : "s")
                .append(" and ").append(resume.offers()).append(" offer")
                .append(resume.offers() == 1 ? "" : "s")
                .append(" from ").append(resume.applications()).append(" application")
                .append(resume.applications() == 1 ? "" : "s");
        if (resume.versionsCompared() > 1) {
            rationale.append(", the best of ").append(resume.versionsCompared())
                    .append(" versions with recorded outcomes");
        }
        return java.util.Optional.of(new Recommendation(1, "RESUME",
                "Use resume version " + resume.recommendedVersion(),
                rationale.toString(), resume.evidence()));
    }

    /**
     * Shared rule for country/skill/company. They differ only in wording, and three near-identical
     * private methods would be three places for the evidence handling to drift apart.
     */
    private java.util.Optional<Recommendation> dimensionRecommendation(
            List<ProductionOptimizationSnapshot.DimensionFinding> findings,
            int priority, String category,
            java.util.function.Function<String, String> action) {

        if (findings.isEmpty()) return java.util.Optional.empty();
        ProductionOptimizationSnapshot.DimensionFinding top = findings.get(0);
        if (top.successRate() == null) return java.util.Optional.empty();

        String rationale = "it converts at " + rate(top.successRate()) + " across "
                + top.applications() + " application" + (top.applications() == 1 ? "" : "s");

        if (findings.size() > 1) {
            ProductionOptimizationSnapshot.DimensionFinding runnerUp = findings.get(1);
            if (runnerUp.successRate() != null) {
                double margin = top.successRate() - runnerUp.successRate();
                if (margin >= MATERIAL_MARGIN) {
                    rationale += ", " + rate(margin) + " ahead of " + runnerUp.key()
                            + " (" + rate(runnerUp.successRate()) + ")";
                } else {
                    // Saying so is the point: a marginal lead is a real finding, and presenting it
                    // as a decisive one would be the fabrication.
                    rationale += ", though only marginally ahead of " + runnerUp.key()
                            + " (" + rate(runnerUp.successRate()) + ")";
                }
            }
        }
        return java.util.Optional.of(new Recommendation(priority, category,
                action.apply(top.key()), rationale, top.evidence()));
    }

    private java.util.Optional<Recommendation> atsRecommendation(ProductionOptimizationSnapshot snapshot) {
        ProductionOptimizationSnapshot.AtsIntelligence ats = snapshot.ats();
        if (ats == null || ats.bestPlatform() == null || ats.bestConfidence() == null) {
            return java.util.Optional.empty();
        }
        // Only worth raising when there is a genuine spread. "Our best ATS is the only one we have
        // tested" is not a recommendation.
        if (ats.weakestPlatform() == null || ats.weakestConfidence() == null) {
            return java.util.Optional.empty();
        }
        int spread = ats.bestConfidence() - ats.weakestConfidence();
        if (spread < MATERIAL_MARGIN) return java.util.Optional.empty();

        return java.util.Optional.of(new Recommendation(5, "ATS",
                "Prefer " + ats.bestPlatform() + " postings for automated application",
                "form automation is measured at " + ats.bestConfidence() + "% confidence there versus "
                        + ats.weakestConfidence() + "% on " + ats.weakestPlatform()
                        + ". " + ats.caveat(),
                ats.evidence()));
    }

    /**
     * Rendered for the Copilot and the dashboard. Returns the explicit no-data sentence rather than
     * an empty string, so a caller can never present silence as "nothing to improve".
     */
    public String render(List<Recommendation> recommendations) {
        if (recommendations == null || recommendations.isEmpty()) {
            return "No verified data available.";
        }
        StringBuilder sb = new StringBuilder();
        for (Recommendation r : recommendations) {
            sb.append("- ").append(r.render()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * One decimal place, with a whole number rendered as such — "31%" rather than "31.0%". The
     * spurious decimal reads as a measured precision the underlying counts do not have.
     */
    private static String rate(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        return rounded == Math.rint(rounded)
                ? (long) rounded + "%"
                : rounded + "%";
    }
}
