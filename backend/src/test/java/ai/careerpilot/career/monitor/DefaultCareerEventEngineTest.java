package ai.careerpilot.career.monitor;

import ai.careerpilot.domain.CareerStrategy;
import ai.careerpilot.domain.Interview;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.offer.Offer;
import ai.careerpilot.repo.CareerStrategyRepository;
import ai.careerpilot.repo.InterviewRepository;
import ai.careerpilot.repo.OfferRepository;
import ai.careerpilot.repo.ResumeRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultCareerEventEngineTest {

    private final ResumeRepository resumes = mock(ResumeRepository.class);
    private final CareerStrategyRepository careerStrategies = mock(CareerStrategyRepository.class);
    private final OfferRepository offers = mock(OfferRepository.class);
    private final InterviewRepository interviews = mock(InterviewRepository.class);
    private final DefaultCareerEventEngine engine = new DefaultCareerEventEngine(
            resumes, careerStrategies, offers, interviews, Duration.ofDays(90), Duration.ofDays(7));

    private UUID userId() {
        return UUID.randomUUID();
    }

    private void stubEmpties(UUID userId) {
        when(resumes.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(careerStrategies.findByUserId(userId)).thenReturn(Optional.empty());
        when(offers.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(interviews.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
    }

    @Test
    void detectsOutdatedResume() {
        UUID userId = userId();
        stubEmpties(userId);
        Resume old = Resume.builder().userId(userId).orgId(UUID.randomUUID())
                .createdAt(Instant.now().minus(Duration.ofDays(120))).build();
        when(resumes.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(old));

        List<CareerAlert> alerts = engine.detectEvents(userId);

        assertThat(alerts).extracting(CareerAlert::type).contains(CareerAlertType.RESUME_OUTDATED);
    }

    @Test
    void freshResumeProducesNoAlert() {
        UUID userId = userId();
        stubEmpties(userId);
        Resume fresh = Resume.builder().userId(userId).orgId(UUID.randomUUID()).createdAt(Instant.now()).build();
        when(resumes.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(fresh));

        assertThat(engine.detectEvents(userId)).extracting(CareerAlert::type).doesNotContain(CareerAlertType.RESUME_OUTDATED);
    }

    @Test
    void detectsLearningSuggestionAndMissingCertificationFromSkillGaps() {
        UUID userId = userId();
        stubEmpties(userId);
        CareerStrategy strategy = CareerStrategy.builder().userId(userId)
                .skillGapsJson("[\"AWS Certification needed\"]").build();
        when(careerStrategies.findByUserId(userId)).thenReturn(Optional.of(strategy));

        List<CareerAlert> alerts = engine.detectEvents(userId);

        assertThat(alerts).extracting(CareerAlert::type)
                .contains(CareerAlertType.LEARNING_SUGGESTION, CareerAlertType.MISSING_CERTIFICATION);
    }

    @Test
    void skillGapsWithoutCertificationKeywordOnlyProducesLearningSuggestion() {
        UUID userId = userId();
        stubEmpties(userId);
        CareerStrategy strategy = CareerStrategy.builder().userId(userId)
                .skillGapsJson("[\"Improve Kubernetes skills\"]").build();
        when(careerStrategies.findByUserId(userId)).thenReturn(Optional.of(strategy));

        List<CareerAlert> alerts = engine.detectEvents(userId);

        assertThat(alerts).extracting(CareerAlert::type).contains(CareerAlertType.LEARNING_SUGGESTION);
        assertThat(alerts).extracting(CareerAlert::type).doesNotContain(CareerAlertType.MISSING_CERTIFICATION);
    }

    @Test
    void detectsPromotionReadinessWhenComputed() {
        UUID userId = userId();
        stubEmpties(userId);
        CareerStrategy strategy = CareerStrategy.builder().userId(userId)
                .promotionReadinessJson("{\"ready\":true}").build();
        when(careerStrategies.findByUserId(userId)).thenReturn(Optional.of(strategy));

        assertThat(engine.detectEvents(userId)).extracting(CareerAlert::type).contains(CareerAlertType.PROMOTION_READY);
    }

    @Test
    void detectsSalaryBelowMarket() {
        UUID userId = userId();
        stubEmpties(userId);
        Offer offer = Offer.builder().userId(userId).baseSalary(new BigDecimal("90000")).marketP50(new BigDecimal("120000")).build();
        when(offers.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(offer));

        assertThat(engine.detectEvents(userId)).extracting(CareerAlert::type).contains(CareerAlertType.SALARY_BELOW_MARKET);
    }

    @Test
    void salaryAtOrAboveMarketProducesNoAlert() {
        UUID userId = userId();
        stubEmpties(userId);
        Offer offer = Offer.builder().userId(userId).baseSalary(new BigDecimal("150000")).marketP50(new BigDecimal("120000")).build();
        when(offers.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(offer));

        assertThat(engine.detectEvents(userId)).extracting(CareerAlert::type).doesNotContain(CareerAlertType.SALARY_BELOW_MARKET);
    }

    @Test
    void detectsUpcomingInterviewWithinWindow() {
        UUID userId = userId();
        stubEmpties(userId);
        Interview interview = Interview.builder().userId(userId).jobId(UUID.randomUUID())
                .interviewType(Interview.TYPE_TECHNICAL).scheduledAt(Instant.now().plus(Duration.ofDays(2))).build();
        when(interviews.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(interview));

        assertThat(engine.detectEvents(userId)).extracting(CareerAlert::type).contains(CareerAlertType.INTERVIEW_REMINDER);
    }

    @Test
    void farFutureInterviewOutsideWindowProducesNoAlert() {
        UUID userId = userId();
        stubEmpties(userId);
        Interview interview = Interview.builder().userId(userId).jobId(UUID.randomUUID())
                .interviewType(Interview.TYPE_TECHNICAL).scheduledAt(Instant.now().plus(Duration.ofDays(30))).build();
        when(interviews.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(interview));

        assertThat(engine.detectEvents(userId)).extracting(CareerAlert::type).doesNotContain(CareerAlertType.INTERVIEW_REMINDER);
    }

    @Test
    void noDataAnywhereProducesNoAlerts() {
        UUID userId = userId();
        stubEmpties(userId);

        assertThat(engine.detectEvents(userId)).isEmpty();
    }
}
