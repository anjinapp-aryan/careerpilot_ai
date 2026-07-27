package ai.careerpilot.career.monitor;

import ai.careerpilot.repo.CareerStrategyRepository;
import ai.careerpilot.repo.InterviewRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.OfferRepository;
import ai.careerpilot.repo.ResumeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Phase 11.5 — the only place any proactive-intelligence bean is constructed, gated by the
 * single {@code career.monitor.enabled} flag (default {@code false}, matching every prior Phase
 * 11 sub-phase's single-flag simplicity). The Spring Data repositories injected here ({@link
 * ResumeRepository}, {@link CareerStrategyRepository}, {@link OfferRepository}, {@link
 * InterviewRepository}, {@link JobRecommendationRepository}) are unconditional, always-present
 * beans (no flag of their own) — unlike the MCP/Capability layers, no {@code ObjectProvider}
 * graceful-absence handling is needed for them.
 */
@Configuration
public class CareerMonitorConfig {

    @Bean
    @ConditionalOnProperty(prefix = "career.monitor", name = "enabled", havingValue = "true")
    public CareerMonitorMetrics careerMonitorMetrics() {
        return new InMemoryCareerMonitorMetrics();
    }

    @Bean
    @ConditionalOnProperty(prefix = "career.monitor", name = "enabled", havingValue = "true")
    public CareerTimeline careerTimeline() {
        return new InMemoryCareerTimeline();
    }

    @Bean
    @ConditionalOnProperty(prefix = "career.monitor", name = "enabled", havingValue = "true")
    public CareerRecommendationEngine careerRecommendationEngine() {
        return new DefaultCareerRecommendationEngine();
    }

    @Bean
    @ConditionalOnProperty(prefix = "career.monitor", name = "enabled", havingValue = "true")
    public CareerOpportunityDetector careerOpportunityDetector(
            JobRecommendationRepository jobRecommendations,
            @Value("${career.monitor.job-match-score-threshold:80}") int matchScoreThreshold,
            @Value("${career.monitor.job-match-lookback-days:7}") long lookbackDays,
            @Value("${career.monitor.job-match-max-alerts:5}") int maxAlerts) {
        return new DefaultCareerOpportunityDetector(jobRecommendations, matchScoreThreshold,
                Duration.ofDays(lookbackDays), maxAlerts);
    }

    @Bean
    @ConditionalOnProperty(prefix = "career.monitor", name = "enabled", havingValue = "true")
    public CareerEventEngine careerEventEngine(
            ResumeRepository resumes, CareerStrategyRepository careerStrategies,
            OfferRepository offers, InterviewRepository interviews,
            @Value("${career.monitor.resume-staleness-days:90}") long resumeStalenessDays,
            @Value("${career.monitor.interview-reminder-window-days:7}") long interviewReminderWindowDays) {
        return new DefaultCareerEventEngine(resumes, careerStrategies, offers, interviews,
                Duration.ofDays(resumeStalenessDays), Duration.ofDays(interviewReminderWindowDays));
    }

    @Bean
    @ConditionalOnProperty(prefix = "career.monitor", name = "enabled", havingValue = "true")
    public CareerMonitor careerMonitor(CareerOpportunityDetector opportunityDetector, CareerEventEngine eventEngine,
                                        CareerRecommendationEngine recommendationEngine, CareerTimeline timeline,
                                        CareerMonitorMetrics metrics,
                                        @Value("${career.monitor.alert-cooldown-days:7}") long cooldownDays,
                                        @Value("${career.monitor.recommendation-limit:10}") int recommendationLimit) {
        return new DefaultCareerMonitor(opportunityDetector, eventEngine, recommendationEngine, timeline, metrics,
                Duration.ofDays(cooldownDays), recommendationLimit);
    }
}
