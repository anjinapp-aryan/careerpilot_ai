package ai.careerpilot.career.monitor;

import ai.careerpilot.repo.CareerStrategyRepository;
import ai.careerpilot.repo.InterviewRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.OfferRepository;
import ai.careerpilot.repo.ResumeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CareerMonitorConfigTest {

    @Configuration
    static class MockRepositories {
        @Bean ResumeRepository resumeRepository() { return mock(ResumeRepository.class); }
        @Bean CareerStrategyRepository careerStrategyRepository() { return mock(CareerStrategyRepository.class); }
        @Bean OfferRepository offerRepository() { return mock(OfferRepository.class); }
        @Bean InterviewRepository interviewRepository() { return mock(InterviewRepository.class); }
        @Bean JobRecommendationRepository jobRecommendationRepository() {
            JobRecommendationRepository repo = mock(JobRecommendationRepository.class);
            when(repo.findByUserIdOrderByMatchScoreDesc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
            return repo;
        }
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MockRepositories.class, CareerMonitorConfig.class);

    @Test
    void withFlagAtDefault_noCareerMonitorBeansAreConstructed() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(CareerMonitor.class);
            assertThat(context).doesNotHaveBean(CareerOpportunityDetector.class);
            assertThat(context).doesNotHaveBean(CareerEventEngine.class);
            assertThat(context).doesNotHaveBean(CareerRecommendationEngine.class);
            assertThat(context).doesNotHaveBean(CareerTimeline.class);
            assertThat(context).doesNotHaveBean(CareerMonitorMetrics.class);
        });
    }

    @Test
    void withFlagOn_allBeansConstructed() {
        contextRunner.withPropertyValues("career.monitor.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(CareerMonitor.class);
            assertThat(context).hasSingleBean(CareerOpportunityDetector.class);
            assertThat(context).hasSingleBean(CareerEventEngine.class);
        });
    }

    @Test
    void endToEnd_wiredMonitorRunsWithoutError() {
        contextRunner.withPropertyValues("career.monitor.enabled=true").run(context -> {
            CareerMonitor monitor = context.getBean(CareerMonitor.class);
            CareerInsights insights = monitor.monitor(UUID.randomUUID());
            assertThat(insights).isNotNull();
        });
    }
}
