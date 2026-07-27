package ai.careerpilot.career.monitor;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DefaultCareerMonitor} — the orchestrator. Covers cooldown suppression, partial
 * detector-failure isolation, and that recommendations are the prioritized (not raw) subset.
 */
class DefaultCareerMonitorTest {

    private final InMemoryCareerTimeline timeline = new InMemoryCareerTimeline();
    private final InMemoryCareerMonitorMetrics metrics = new InMemoryCareerMonitorMetrics();
    private final DefaultCareerRecommendationEngine recommendationEngine = new DefaultCareerRecommendationEngine();

    private CareerAlert alert(UUID userId, CareerAlertType type) {
        return CareerAlert.of(userId, type, CareerAlertSeverity.HIGH, "msg", Map.of());
    }

    @Test
    void combinesOpportunitiesAndEvents() {
        UUID userId = UUID.randomUUID();
        CareerOpportunityDetector opportunityDetector = uid -> List.of(alert(userId, CareerAlertType.JOB_MATCH));
        CareerEventEngine eventEngine = uid -> List.of(alert(userId, CareerAlertType.RESUME_OUTDATED));
        DefaultCareerMonitor monitor = new DefaultCareerMonitor(opportunityDetector, eventEngine, recommendationEngine,
                timeline, metrics, Duration.ofDays(7), 10);

        CareerInsights insights = monitor.monitor(userId);

        assertThat(insights.alerts()).hasSize(2);
        assertThat(insights.recommendations()).hasSize(2);
    }

    @Test
    void suppressesAlertsAlreadySurfacedWithinCooldown() {
        UUID userId = UUID.randomUUID();
        timeline.record(alert(userId, CareerAlertType.JOB_MATCH));
        CareerOpportunityDetector opportunityDetector = uid -> List.of(alert(userId, CareerAlertType.JOB_MATCH));
        CareerEventEngine eventEngine = uid -> List.of();
        DefaultCareerMonitor monitor = new DefaultCareerMonitor(opportunityDetector, eventEngine, recommendationEngine,
                timeline, metrics, Duration.ofDays(7), 10);

        CareerInsights insights = monitor.monitor(userId);

        assertThat(insights.alerts()).hasSize(1);
        assertThat(insights.recommendations()).isEmpty();
        assertThat(metrics.suppressedCount("JOB_MATCH")).isEqualTo(1);
    }

    @Test
    void opportunityDetectorFailureDoesNotBlockEventEngine() {
        UUID userId = UUID.randomUUID();
        CareerOpportunityDetector throwingDetector = uid -> { throw new RuntimeException("boom"); };
        CareerEventEngine eventEngine = uid -> List.of(alert(userId, CareerAlertType.RESUME_OUTDATED));
        DefaultCareerMonitor monitor = new DefaultCareerMonitor(throwingDetector, eventEngine, recommendationEngine,
                timeline, metrics, Duration.ofDays(7), 10);

        CareerInsights insights = monitor.monitor(userId);

        assertThat(insights.recommendations()).hasSize(1);
        assertThat(insights.recommendations().get(0).type()).isEqualTo(CareerAlertType.RESUME_OUTDATED);
    }

    @Test
    void noAlertsProducesEmptyInsightsWithNoNewInsightsSummary() {
        UUID userId = UUID.randomUUID();
        CareerOpportunityDetector opportunityDetector = uid -> List.of();
        CareerEventEngine eventEngine = uid -> List.of();
        DefaultCareerMonitor monitor = new DefaultCareerMonitor(opportunityDetector, eventEngine, recommendationEngine,
                timeline, metrics, Duration.ofDays(7), 10);

        CareerInsights insights = monitor.monitor(userId);

        assertThat(insights.recommendations()).isEmpty();
        assertThat(insights.summary()).contains("No new proactive insights");
    }
}
