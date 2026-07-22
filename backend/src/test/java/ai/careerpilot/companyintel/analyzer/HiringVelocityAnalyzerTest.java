package ai.careerpilot.companyintel.analyzer;

import ai.careerpilot.domain.CompanyTimelineEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 7.17.3 — time-to-hire from real timeline timestamps; department/seasonal explicitly not fabricated. */
class HiringVelocityAnalyzerTest {

    private final HiringVelocityAnalyzer analyzer = new HiringVelocityAnalyzer();

    @Test
    void noEventsYieldsNullAvgTimeToHire() {
        Map<String, Object> m = analyzer.metrics(List.of());
        assertThat(m.get("avgTimeToHireDays")).isNull();
    }

    @Test
    void computesAverageDaysBetweenApplicationAndOfferForMatchingRefId() {
        UUID jobId = UUID.randomUUID();
        Instant applied = Instant.now().minus(10, ChronoUnit.DAYS);
        Instant offered = Instant.now();
        List<CompanyTimelineEvent> events = List.of(
                CompanyTimelineEvent.builder().eventType("APPLICATION_SUBMITTED").refId(jobId).occurredAt(applied).build(),
                CompanyTimelineEvent.builder().eventType("OFFER_RECEIVED").refId(jobId).occurredAt(offered).build());

        Map<String, Object> m = analyzer.metrics(events);

        assertThat((Double) m.get("avgTimeToHireDays")).isCloseTo(10.0, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void unmatchedOfferWithNoApplicationIsIgnored() {
        List<CompanyTimelineEvent> events = List.of(
                CompanyTimelineEvent.builder().eventType("OFFER_RECEIVED").refId(UUID.randomUUID()).occurredAt(Instant.now()).build());

        Map<String, Object> m = analyzer.metrics(events);

        assertThat(m.get("avgTimeToHireDays")).isNull();
    }

    @Test
    void departmentAndSeasonalHiringAreNeverFabricated() {
        Map<String, Object> m = analyzer.metrics(List.of());
        assertThat(m.get("departmentHiring")).isNull();
        assertThat((String) m.get("departmentHiringNote")).contains("not computed");
        assertThat(m.get("seasonalHiring")).isNull();
        assertThat((String) m.get("seasonalHiringNote")).contains("not computed");
    }

    @Test
    void analyzeReturnsEmptyWhenNoTimeToHireSignal() {
        assertThat(analyzer.analyze(List.of())).isEmpty();
    }

    @Test
    void analyzeReturnsInsightWhenSignalExists() {
        UUID jobId = UUID.randomUUID();
        List<CompanyTimelineEvent> events = List.of(
                CompanyTimelineEvent.builder().eventType("APPLICATION_SUBMITTED").refId(jobId)
                        .occurredAt(Instant.now().minus(5, ChronoUnit.DAYS)).build(),
                CompanyTimelineEvent.builder().eventType("OFFER_RECEIVED").refId(jobId).occurredAt(Instant.now()).build());

        assertThat(analyzer.analyze(events)).isPresent();
    }
}
