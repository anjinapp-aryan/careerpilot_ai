package ai.careerpilot.career.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Phase 11.5 — the default {@link CareerMonitor}. Runs both detectors (isolated try/catch each,
 * so one failing never blocks the other — matching the never-fail discipline established
 * throughout Phases 10–11), filters out anything the {@link CareerTimeline} says was already
 * surfaced within the cooldown window, records the survivors, and hands the rest to {@link
 * CareerRecommendationEngine} for final ranking.
 */
public class DefaultCareerMonitor implements CareerMonitor {

    private static final Logger log = LoggerFactory.getLogger(DefaultCareerMonitor.class);

    private final CareerOpportunityDetector opportunityDetector;
    private final CareerEventEngine eventEngine;
    private final CareerRecommendationEngine recommendationEngine;
    private final CareerTimeline timeline;
    private final CareerMonitorMetrics metrics;
    private final Duration cooldown;
    private final int recommendationLimit;

    public DefaultCareerMonitor(CareerOpportunityDetector opportunityDetector, CareerEventEngine eventEngine,
                                 CareerRecommendationEngine recommendationEngine, CareerTimeline timeline,
                                 CareerMonitorMetrics metrics, Duration cooldown, int recommendationLimit) {
        this.opportunityDetector = opportunityDetector;
        this.eventEngine = eventEngine;
        this.recommendationEngine = recommendationEngine;
        this.timeline = timeline;
        this.metrics = metrics;
        this.cooldown = cooldown;
        this.recommendationLimit = recommendationLimit;
    }

    @Override
    public CareerInsights monitor(UUID userId) {
        long start = System.currentTimeMillis();

        List<CareerAlert> opportunities = safelyDetect(() -> opportunityDetector.detectOpportunities(userId), "opportunity");
        List<CareerAlert> events = safelyDetect(() -> eventEngine.detectEvents(userId), "event");
        List<CareerAlert> all = Stream.concat(opportunities.stream(), events.stream()).toList();
        all.forEach(a -> metrics.recordAlertDetected(a.type().name()));

        List<CareerAlert> fresh = new ArrayList<>();
        for (CareerAlert alert : all) {
            if (timeline.wasRecentlySurfaced(userId, alert.type(), cooldown)) {
                metrics.recordAlertSuppressed(alert.type().name());
            } else {
                fresh.add(alert);
                timeline.record(alert);
            }
        }

        List<CareerAlert> recommendations = recommendationEngine.prioritize(fresh, recommendationLimit);
        metrics.recordMonitorRunLatency(System.currentTimeMillis() - start);

        return new CareerInsights(userId, all, recommendations, Instant.now(), summarize(recommendations));
    }

    private List<CareerAlert> safelyDetect(java.util.function.Supplier<List<CareerAlert>> detection, String label) {
        try {
            List<CareerAlert> result = detection.get();
            return result == null ? List.of() : result;
        } catch (Exception e) {
            log.warn("Career {} detection failed, continuing with the other detector: {}", label, e.toString());
            return List.of();
        }
    }

    private String summarize(List<CareerAlert> recommendations) {
        if (recommendations.isEmpty()) {
            return "No new proactive insights right now.";
        }
        Map<CareerAlertType, Long> counts = recommendations.stream()
                .collect(Collectors.groupingBy(CareerAlert::type, LinkedHashMap::new, Collectors.counting()));
        String breakdown = counts.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey())
                .collect(Collectors.joining(", "));
        return recommendations.size() + " proactive insight(s): " + breakdown;
    }
}
