package ai.careerpilot.career.monitor;

import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.repo.JobRecommendationRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultCareerOpportunityDetectorTest {

    private final JobRecommendationRepository repo = mock(JobRecommendationRepository.class);
    private final DefaultCareerOpportunityDetector detector =
            new DefaultCareerOpportunityDetector(repo, 80, Duration.ofDays(7), 5);

    private JobRecommendation rec(UUID userId, int matchScore, Instant createdAt) {
        return JobRecommendation.builder().userId(userId).jobId(UUID.randomUUID())
                .matchScore(matchScore).createdAt(createdAt).build();
    }

    @Test
    void alertsOnRecentHighMatchRecommendation() {
        UUID userId = UUID.randomUUID();
        when(repo.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of(rec(userId, 90, Instant.now())));

        List<CareerAlert> alerts = detector.detectOpportunities(userId);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).type()).isEqualTo(CareerAlertType.JOB_MATCH);
        assertThat(alerts.get(0).severity()).isEqualTo(CareerAlertSeverity.HIGH);
    }

    @Test
    void ignoresBelowThresholdMatches() {
        UUID userId = UUID.randomUUID();
        when(repo.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of(rec(userId, 60, Instant.now())));

        assertThat(detector.detectOpportunities(userId)).isEmpty();
    }

    @Test
    void ignoresStaleRecommendationsOutsideLookbackWindow() {
        UUID userId = UUID.randomUUID();
        when(repo.findByUserIdOrderByMatchScoreDesc(userId))
                .thenReturn(List.of(rec(userId, 95, Instant.now().minus(Duration.ofDays(30)))));

        assertThat(detector.detectOpportunities(userId)).isEmpty();
    }

    @Test
    void capsAtMaxAlerts() {
        UUID userId = UUID.randomUUID();
        List<JobRecommendation> many = List.of(
                rec(userId, 99, Instant.now()), rec(userId, 98, Instant.now()), rec(userId, 97, Instant.now()),
                rec(userId, 96, Instant.now()), rec(userId, 95, Instant.now()), rec(userId, 94, Instant.now()));
        when(repo.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(many);

        assertThat(detector.detectOpportunities(userId)).hasSize(5);
    }

    @Test
    void noRecommendationsProducesNoAlerts() {
        UUID userId = UUID.randomUUID();
        when(repo.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of());

        assertThat(detector.detectOpportunities(userId)).isEmpty();
    }
}
