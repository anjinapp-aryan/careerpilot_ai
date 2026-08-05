package ai.careerpilot.intelligence.api;

import ai.careerpilot.intelligence.OptimizationRecommendationService;
import ai.careerpilot.intelligence.ProductionIntelligenceService;
import ai.careerpilot.intelligence.ProductionOptimizationSnapshot;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 13C — the single read surface for production optimization evidence.
 *
 * <p><b>One endpoint, several consumers.</b> The Resume Optimizer's "recommended version" panel
 * (Part 2), the Mission dashboard's guidance strip (Part 3) and the Dashboard's optimization cards
 * (Part 7) all want the same evidence from the same snapshot. Three endpoints would be three places
 * for the shape to drift and three chances for one of them to render a figure the others don't
 * have; they read the same payload instead and each renders the slice it needs.
 *
 * <p><b>Authenticated and per-user.</b> Unlike the aggregate ATS campaign report on
 * {@code /api/diagnostics/browser}, everything here is one person's own outcome history — how many
 * interviews they got, from which companies. That is squarely personal data and never belongs on
 * the unauthenticated diagnostics surface.
 *
 * <p>Additive: no existing endpoint, DTO or response shape changed. Returns {@code 200} with an
 * explicit empty payload rather than {@code 404} when the feature is dark or the user has no
 * history, so a client can tell "disabled" and "no evidence yet" apart instead of collapsing both
 * into an error — the same convention {@code GET /api/career-timeline} already established.
 */
@RestController
@RequestMapping("/api/intelligence")
public class ProductionIntelligenceController {

    private final ProductionIntelligenceService intelligence;
    private final OptimizationRecommendationService recommendations;

    public ProductionIntelligenceController(ProductionIntelligenceService intelligence,
                                            OptimizationRecommendationService recommendations) {
        this.intelligence = intelligence;
        this.recommendations = recommendations;
    }

    /**
     * The snapshot plus its ranked recommendations, both derived from the same read so the two can
     * never describe different states of the world.
     */
    @GetMapping("/optimization")
    public Map<String, Object> optimization(AuthenticatedUser user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", intelligence.isEnabled());

        if (!intelligence.isEnabled()) {
            out.put("snapshot", null);
            out.put("recommendations", List.of());
            out.put("message", "Production intelligence is disabled.");
            return out;
        }

        ProductionOptimizationSnapshot snapshot = intelligence.getSnapshot(user.userId());
        List<OptimizationRecommendationService.Recommendation> ranked = recommendations.recommend(snapshot);

        out.put("snapshot", snapshot.snapshot());
        out.put("recommendations", ranked.stream()
                .map(OptimizationRecommendationService.Recommendation::snapshot).toList());
        // The same sentence the Copilot uses, so a UI that renders this verbatim cannot present
        // silence as "nothing to improve".
        out.put("message", ranked.isEmpty() ? "No verified data available." : null);
        return out;
    }
}
