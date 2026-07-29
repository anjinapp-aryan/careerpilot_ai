package ai.careerpilot.dailydiscovery;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.domain.DailyCareerSummary;
import ai.careerpilot.domain.User;
import ai.careerpilot.learning.career.executive.ExecutiveDecisionEngine;
import ai.careerpilot.mission.MissionAwareDailyBriefService;
import ai.careerpilot.mission.MissionAwareDailyBriefService.MissionBrief;
import ai.careerpilot.repo.CareerIntelligenceRepository;
import ai.careerpilot.repo.DailyCareerSummaryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 6A — {@link DailyCareerSummaryGenerator}'s Mission-Aware integration point. Pins that the
 * new columns are populated when {@link MissionAwareDailyBriefService} returns data, stay null
 * (byte-for-byte the pre-Phase-6A row shape) when it returns empty, and that a brief-building
 * failure never stops the summary itself from being persisted.
 */
class DailyCareerSummaryGeneratorTest {

    private final AiGatewayService ai = mock(AiGatewayService.class);
    private final DailyCareerSummaryRepository summaries = mock(DailyCareerSummaryRepository.class);
    private final CareerIntelligenceRepository careerIntelligence = mock(CareerIntelligenceRepository.class);
    private final ExecutiveDecisionEngine executiveDecisionEngine = mock(ExecutiveDecisionEngine.class);
    private final MissionAwareDailyBriefService missionBrief = mock(MissionAwareDailyBriefService.class);

    private DailyCareerSummaryGenerator generator() {
        return new DailyCareerSummaryGenerator(ai, summaries, careerIntelligence, executiveDecisionEngine, missionBrief, true);
    }

    private User user() {
        return User.builder().id(UUID.randomUUID()).fullName("Ada Lovelace").build();
    }

    private DailyJobDiscoveryService.UserDiscoverySnapshot snapshot() {
        return new DailyJobDiscoveryService.UserDiscoverySnapshot(
                5, 2, 3, 1, 0, 2, 3, BigDecimal.valueOf(80),
                List.of("Acme"), List.of("Java"), Map.of(), Map.of(), Map.of(), Map.of());
    }

    @Test
    void missionBriefFieldsArePopulatedWhenServiceReturnsData() {
        User user = user();
        when(careerIntelligence.findByUserIdAndDimensionOrderByComputedAtDesc(any(), any())).thenReturn(List.of());
        when(executiveDecisionEngine.isEnabled()).thenReturn(false);
        when(ai.chat(any(), any())).thenReturn("summary text");
        MissionBrief brief = new MissionBrief(UUID.randomUUID(), "Become Principal Engineer Abroad", 53,
                "Netherlands", "Germany", List.of("Finish Kafka Learning"), List.of("JOB_DISCOVERY_V1"),
                List.of("Kubernetes gap"), List.of("Improve Kubernetes knowledge"), List.of("reason"),
                List.of(), "~70 days remaining (of a 90-day plan)",
                "Delay interview applications until Kafka learning milestone is complete.");
        when(missionBrief.buildFor(user.getId())).thenReturn(Optional.of(brief));

        generator().generate(UUID.randomUUID(), user, snapshot());

        ArgumentCaptor<DailyCareerSummary> captor = ArgumentCaptor.forClass(DailyCareerSummary.class);
        verify(summaries).save(captor.capture());
        DailyCareerSummary saved = captor.getValue();
        assertThat(saved.getMissionName()).isEqualTo("Become Principal Engineer Abroad");
        assertThat(saved.getMissionProgressPercent()).isEqualTo(53);
        assertThat(saved.getCurrentStrategyCountry()).isEqualTo("Netherlands");
        assertThat(saved.getAlternativeStrategyCountry()).isEqualTo("Germany");
        assertThat(saved.getTodaysMissionTasksJson()).contains("Finish Kafka Learning");
        assertThat(saved.getMissionRecommendation()).contains("Delay interview applications");
    }

    @Test
    void missionFieldsStayNullWhenBriefServiceReturnsEmpty() {
        User user = user();
        when(careerIntelligence.findByUserIdAndDimensionOrderByComputedAtDesc(any(), any())).thenReturn(List.of());
        when(executiveDecisionEngine.isEnabled()).thenReturn(false);
        when(ai.chat(any(), any())).thenReturn("summary text");
        when(missionBrief.buildFor(user.getId())).thenReturn(Optional.empty());

        generator().generate(UUID.randomUUID(), user, snapshot());

        ArgumentCaptor<DailyCareerSummary> captor = ArgumentCaptor.forClass(DailyCareerSummary.class);
        verify(summaries).save(captor.capture());
        DailyCareerSummary saved = captor.getValue();
        assertThat(saved.getMissionName()).isNull();
        assertThat(saved.getMissionProgressPercent()).isNull();
        assertThat(saved.getCurrentStrategyCountry()).isNull();
        // Pre-existing fields are unaffected either way.
        assertThat(saved.getRecommendedCount()).isEqualTo(5);
    }

    @Test
    void missionBriefFailureStillPersistsTheSummary() {
        User user = user();
        when(careerIntelligence.findByUserIdAndDimensionOrderByComputedAtDesc(any(), any())).thenReturn(List.of());
        when(executiveDecisionEngine.isEnabled()).thenReturn(false);
        when(ai.chat(any(), any())).thenReturn("summary text");
        when(missionBrief.buildFor(user.getId())).thenThrow(new RuntimeException("boom"));

        generator().generate(UUID.randomUUID(), user, snapshot());

        verify(summaries).save(any(DailyCareerSummary.class));
    }
}
