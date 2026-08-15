package ai.careerpilot.service;

import ai.careerpilot.api.dto.JobRecommendationDtos.RecommendedJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Global Job Discovery Expansion — bounded country diversification over an already-ranked
 * {@code RecommendedJob} list. Deliberately does NOT re-score anything: it only reorders within
 * fixed 5-point score bands (95-100, 90-94, ...), so a job's relevance tier never changes and a
 * high-scoring job in one country can never be displaced by a meaningfully lower-scoring job in
 * another — e.g. a 94% Germany job stays in the 90-94 band regardless of how many other Germany
 * jobs surround it; a 55% UAE job (50-54 band) can never be promoted above it.
 *
 * <p>Within a band, countries are interleaved round-robin (each country's own relative order,
 * highest-scoring-first, is preserved) so one country with many matches can't fill the entire
 * band before a candidate ever sees a second country. Pure and deterministic — no LLM, no I/O.
 *
 * <p>Gated by {@code career.international.diversification.enabled} (default {@code false}):
 * {@link #diversify(List)} returns the input list unchanged when off, matching this codebase's
 * "master switch = byte-identical no-op" convention.
 *
 * <p><b>International Job Discovery Phase 2 — soft quota weighting.</b> A second, independent
 * flag ({@code career.international.quota-diversification.enabled}) changes only the pull ORDER
 * within a band: instead of plain round-robin insertion order, countries are pulled in order of
 * {@link #SOFT_QUOTA_PCT} (higher target share pulled first each round). This is guidance, not a
 * hard cap — a band with only Germany jobs still returns only Germany jobs (see {@link
 * #interleaveByCountry}'s single-country short-circuit, unchanged) — and it can never affect which
 * band a job lands in, so the "94 never displaced by 55" invariant is untouched regardless of
 * either flag.
 */
@Component
public class RecommendationDiversifier {

    private static final int BAND_WIDTH = 5;

    /**
     * Soft target share (not enforced as a hard cap) from the International Job Discovery Phase 2
     * country-priority strategy. Keyed by the same display-name string {@code Job#getCountry()}
     * already uses (e.g. "Germany"), matching {@link #interleaveByCountry}'s existing key space.
     * A country absent from this map (e.g. India, or any country outside the 10-country strategy)
     * simply falls back to insertion order — never excluded, never penalized.
     */
    private static final Map<String, Integer> SOFT_QUOTA_PCT = Map.ofEntries(
            Map.entry("Germany", 25), Map.entry("Ireland", 15), Map.entry("Netherlands", 13),
            Map.entry("United Kingdom", 12), Map.entry("Luxembourg", 5), Map.entry("Poland", 9),
            Map.entry("Singapore", 5), Map.entry("Canada", 5), Map.entry("Australia", 4),
            Map.entry("United States", 2));

    private final boolean enabled;
    private final boolean quotaWeighted;

    public RecommendationDiversifier(
            @Value("${career.international.diversification.enabled:false}") boolean enabled,
            @Value("${career.international.quota-diversification.enabled:false}") boolean quotaWeighted) {
        this.enabled = enabled;
        this.quotaWeighted = quotaWeighted;
    }

    public List<RecommendedJob> diversify(List<RecommendedJob> ranked) {
        if (!enabled || ranked == null || ranked.size() < 2) return ranked;

        LinkedHashMap<Integer, List<RecommendedJob>> bands = new LinkedHashMap<>();
        for (RecommendedJob rj : ranked) {
            int band = rj.matchScore() / BAND_WIDTH;
            bands.computeIfAbsent(band, b -> new ArrayList<>()).add(rj);
        }

        List<RecommendedJob> out = new ArrayList<>(ranked.size());
        for (List<RecommendedJob> band : bands.values()) {
            out.addAll(interleaveByCountry(band));
        }
        return out;
    }

    private List<RecommendedJob> interleaveByCountry(List<RecommendedJob> band) {
        if (band.size() < 2) return band;

        LinkedHashMap<String, Deque<RecommendedJob>> byCountry = new LinkedHashMap<>();
        for (RecommendedJob rj : band) {
            String country = rj.job().getCountry() == null ? "UNKNOWN" : rj.job().getCountry();
            byCountry.computeIfAbsent(country, c -> new ArrayDeque<>()).add(rj);
        }
        if (byCountry.size() < 2) return band; // single country in this band — nothing to diversify

        List<Deque<RecommendedJob>> pullOrder = new ArrayList<>(byCountry.values());
        if (quotaWeighted) {
            // Stable sort: higher soft-quota countries are pulled first each round; a country
            // absent from SOFT_QUOTA_PCT (quota 0) keeps its original insertion-order position
            // relative to other absent countries.
            List<String> countries = new ArrayList<>(byCountry.keySet());
            pullOrder = new ArrayList<>(countries.size());
            countries.sort(Comparator.comparingInt((String c) -> SOFT_QUOTA_PCT.getOrDefault(c, 0)).reversed());
            for (String c : countries) pullOrder.add(byCountry.get(c));
        }

        List<RecommendedJob> out = new ArrayList<>(band.size());
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (Deque<RecommendedJob> queue : pullOrder) {
                RecommendedJob next = queue.poll();
                if (next != null) {
                    out.add(next);
                    progressed = true;
                }
            }
        }
        return out;
    }
}
