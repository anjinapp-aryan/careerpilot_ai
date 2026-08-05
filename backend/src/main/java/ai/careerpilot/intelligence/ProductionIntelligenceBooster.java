package ai.careerpilot.intelligence;

import ai.careerpilot.domain.Job;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * Phase 13C — applies a small, evidence-backed adjustment to a job's match score from the
 * {@link ProductionOptimizationSnapshot} this user's own outcomes already produced.
 *
 * <p><b>This is not a scoring engine.</b> It is the fourth booster in an established seam —
 * alongside {@code LearningRecommendationBooster}, the company-knowledge booster and the
 * career-memory booster — all of which follow the identical shape in
 * {@code JobMatchingService}: {@code isActive()} gate, bounded integer boost, clamped to
 * {@code [0,100]}, and {@code base} returned unchanged when dark. Nothing about
 * {@code JobScoring.scoreV2}'s own weights is touched.
 *
 * <h2>The snapshot is passed in, never fetched here</h2>
 * {@code refreshForUser} scores a pool of hundreds of jobs in one stream. A booster that resolved
 * its own snapshot would issue four repository reads <em>per job</em> — the N+1 this phase
 * explicitly forbids. The caller resolves it once per refresh and hands the same immutable object
 * to every call, so the marginal cost of this booster across the whole pool is zero queries.
 *
 * <h2>Bounds</h2>
 * ±{@code MAX_TOTAL} points against a 0–100 score, which is the ±10% ceiling the phase specifies.
 * Each contributing dimension is individually capped so no single signal can consume the whole
 * budget — a strong country signal must not be able to outweigh everything else about a job.
 *
 * <p>Only findings that already passed {@link Evidence}'s actionable floor can contribute: the
 * snapshot dropped everything below it before this class ever sees it, so a boost derived from two
 * applications is not something this class must remember to avoid.
 */
@Component
public class ProductionIntelligenceBooster {

    /** ±10% of a 0–100 score, as specified. */
    static final int MAX_TOTAL = 10;
    /** Per-dimension caps. They sum to more than MAX_TOTAL; the total clamp is the real bound. */
    static final int MAX_COUNTRY = 5;
    static final int MAX_COMPANY = 5;
    static final int MAX_SKILL = 4;

    /**
     * Success rates above this are treated as evidence to boost, below it as evidence to discount.
     * A neutral midpoint rather than a tuned constant: the claim being made is only "this converts
     * better than that", and anchoring it anywhere else would smuggle in an absolute judgement
     * about what a good conversion rate is.
     */
    private static final double NEUTRAL_RATE = 20.0;

    private final boolean enabled;

    public ProductionIntelligenceBooster(
            @Value("${jobs.matching.production-intelligence-boost.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isActive() {
        return enabled;
    }

    /**
     * The bounded adjustment for one job. Pure — no I/O, no state.
     *
     * @param snapshot resolved once per refresh by the caller; {@code null} yields no adjustment
     * @return points to add to the match score, in {@code [-MAX_TOTAL, +MAX_TOTAL]}
     */
    public int computeBoost(ProductionOptimizationSnapshot snapshot, Job job) {
        if (!enabled || snapshot == null || job == null || snapshot.isEmpty()) return 0;

        int total = 0;
        total += dimensionAdjustment(snapshot.countries(), countryOf(job), MAX_COUNTRY);
        total += dimensionAdjustment(snapshot.companies(), job.getCompany(), MAX_COMPANY);
        total += skillAdjustment(snapshot, job);

        return Math.max(-MAX_TOTAL, Math.min(MAX_TOTAL, total));
    }

    /**
     * Matches a job's attribute against a learned dimension and scales the distance from neutral
     * into the cap.
     *
     * <p>An attribute the snapshot has never seen contributes <b>zero</b>, not a penalty. "We have
     * no evidence about this country" and "this country performs badly" are different statements,
     * and conflating them would quietly suppress every job in an unexplored market — which is
     * exactly the market a candidate most needs surfaced.
     */
    private static int dimensionAdjustment(
            java.util.List<ProductionOptimizationSnapshot.DimensionFinding> findings,
            String attribute, int cap) {

        if (attribute == null || attribute.isBlank() || findings.isEmpty()) return 0;
        String needle = attribute.trim().toLowerCase(Locale.ROOT);

        for (ProductionOptimizationSnapshot.DimensionFinding finding : findings) {
            if (finding.key() == null || finding.successRate() == null) continue;
            if (!matches(needle, finding.key())) continue;
            return scale(finding.successRate(), cap);
        }
        return 0;
    }

    /**
     * Skills are the one dimension where a job carries many values at once, so the strongest single
     * match is used rather than a sum. Summing would let a job listing ten skills accumulate a
     * larger adjustment than one listing the two that actually matter.
     */
    private static int skillAdjustment(ProductionOptimizationSnapshot snapshot, Job job) {
        if (snapshot.skills().isEmpty()) return 0;
        String haystack = ((job.getSkills() == null ? "" : job.getSkills()) + " "
                + (job.getTitle() == null ? "" : job.getTitle())).toLowerCase(Locale.ROOT);
        if (haystack.isBlank()) return 0;

        int best = 0;
        for (ProductionOptimizationSnapshot.DimensionFinding finding : snapshot.skills()) {
            if (finding.key() == null || finding.successRate() == null) continue;
            if (!haystack.contains(finding.key().trim().toLowerCase(Locale.ROOT))) continue;
            int adjustment = scale(finding.successRate(), MAX_SKILL);
            if (Math.abs(adjustment) > Math.abs(best)) best = adjustment;
        }
        return best;
    }

    /**
     * Maps a success rate onto {@code [-cap, +cap]} linearly around {@link #NEUTRAL_RATE}, so a
     * rate far from neutral moves the score further than one just off it. Saturates at twice
     * neutral, past which additional rate buys no additional boost — an 80% conversion rate on a
     * handful of applications should not dominate a ranking.
     */
    private static int scale(double successRate, int cap) {
        double distance = (successRate - NEUTRAL_RATE) / NEUTRAL_RATE;
        double clamped = Math.max(-1.0, Math.min(1.0, distance));
        return (int) Math.round(clamped * cap);
    }

    /** Exact or containment match, case-insensitive — company names vary by suffix ("Acme"/"Acme Inc"). */
    private static boolean matches(String needle, String key) {
        String k = key.trim().toLowerCase(Locale.ROOT);
        return k.equals(needle) || k.contains(needle) || needle.contains(k);
    }

    /**
     * A job's country. Falls back to the location string, since discovered jobs frequently carry
     * only that — and {@code SuccessPattern}'s LOCATION dimension is populated from the same kind
     * of free text, so the two genuinely compare.
     */
    private static String countryOf(Job job) {
        if (job.getCountry() != null && !job.getCountry().isBlank()) return job.getCountry();
        return job.getLocation();
    }

    /** Diagnostics/audit line explaining one adjustment. Never invents a figure. */
    public String explain(ProductionOptimizationSnapshot snapshot, Job job, UUID userId) {
        int boost = computeBoost(snapshot, job);
        if (boost == 0) return "no production-evidence adjustment applied";
        return "production evidence adjusted this match by " + (boost > 0 ? "+" : "") + boost
                + " point(s) for user " + userId;
    }
}
