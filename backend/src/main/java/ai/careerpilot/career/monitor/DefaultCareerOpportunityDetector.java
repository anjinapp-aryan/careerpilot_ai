package ai.careerpilot.career.monitor;

import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.repo.JobRecommendationRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 11.5 — the default {@link CareerOpportunityDetector}. A thin read-only wrapper around
 * the existing {@link JobRecommendationRepository} (Phase 2C) — no new query, no new table,
 * exactly the same "existing finder methods only" discipline as every Phase 10.2 MCP tool.
 * Alerts on recommendations at or above {@code matchScoreThreshold} that were computed within
 * {@code lookback}, capped at {@code maxAlerts} (highest match score first) so a user with a
 * large recommendation pool isn't flooded.
 */
public class DefaultCareerOpportunityDetector implements CareerOpportunityDetector {

    private final JobRecommendationRepository jobRecommendations;
    private final int matchScoreThreshold;
    private final Duration lookback;
    private final int maxAlerts;

    public DefaultCareerOpportunityDetector(JobRecommendationRepository jobRecommendations,
                                             int matchScoreThreshold, Duration lookback, int maxAlerts) {
        this.jobRecommendations = jobRecommendations;
        this.matchScoreThreshold = matchScoreThreshold;
        this.lookback = lookback;
        this.maxAlerts = maxAlerts;
    }

    @Override
    public List<CareerAlert> detectOpportunities(UUID userId) {
        Instant cutoff = Instant.now().minus(lookback);
        return jobRecommendations.findByUserIdOrderByMatchScoreDesc(userId).stream()
                .filter(rec -> rec.getMatchScore() >= matchScoreThreshold)
                .filter(rec -> rec.getCreatedAt() != null && rec.getCreatedAt().isAfter(cutoff))
                .limit(maxAlerts)
                .map(this::toAlert)
                .toList();
    }

    private CareerAlert toAlert(JobRecommendation rec) {
        CareerAlertSeverity severity = rec.getMatchScore() >= 90 ? CareerAlertSeverity.HIGH : CareerAlertSeverity.MEDIUM;
        String message = "New job match (" + rec.getMatchScore() + "% match) worth reviewing";
        return CareerAlert.of(rec.getUserId(), CareerAlertType.JOB_MATCH, severity, message,
                Map.of("jobId", rec.getJobId(), "matchScore", rec.getMatchScore(),
                        "category", rec.getCategory() == null ? "" : rec.getCategory()));
    }
}
