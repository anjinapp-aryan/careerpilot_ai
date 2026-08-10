package ai.careerpilot.service;

import ai.careerpilot.api.dto.JobRecommendationDtos.RecommendedJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;

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
 */
@Component
public class RecommendationDiversifier {

    private static final int BAND_WIDTH = 5;

    private final boolean enabled;

    public RecommendationDiversifier(
            @Value("${career.international.diversification.enabled:false}") boolean enabled) {
        this.enabled = enabled;
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

        List<RecommendedJob> out = new ArrayList<>(band.size());
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (Deque<RecommendedJob> queue : byCountry.values()) {
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
