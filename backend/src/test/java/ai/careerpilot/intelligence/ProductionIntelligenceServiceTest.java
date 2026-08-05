package ai.careerpilot.intelligence;

import ai.careerpilot.domain.ResumeLearning;
import ai.careerpilot.domain.SuccessPattern;
import ai.careerpilot.execution.browser.validation.ValidationHistoryService;
import ai.careerpilot.repo.ResumeLearningRepository;
import ai.careerpilot.repo.SuccessPatternRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 13B — the orchestrator. Two things are being protected: that it never emits a finding
 * without enough real evidence, and that it never re-derives an analysis some existing service
 * already owns.
 */
class ProductionIntelligenceServiceTest {

    private final UUID userId = UUID.randomUUID();

    private ResumeLearningRepository resumeLearning;
    private SuccessPatternRepository successPatterns;
    private ValidationHistoryService validationHistory;

    @BeforeEach
    void setUp() {
        resumeLearning = mock(ResumeLearningRepository.class);
        successPatterns = mock(SuccessPatternRepository.class);
        validationHistory = mock(ValidationHistoryService.class);
        when(resumeLearning.findByUserIdOrderByOfferRateDesc(userId)).thenReturn(List.of());
        when(successPatterns.findByUserIdAndDimensionOrderBySuccessRateDesc(any(), anyString()))
                .thenReturn(List.of());
        when(validationHistory.isEnabled()).thenReturn(false);
    }

    @SuppressWarnings("unchecked")
    private ProductionIntelligenceService service(boolean enabled) {
        ObjectProvider<ValidationHistoryService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(validationHistory);
        return new ProductionIntelligenceService(resumeLearning, successPatterns, provider, enabled);
    }

    private static ResumeLearning version(String name, int applications, int interviews,
                                          int offers, boolean best) {
        return ResumeLearning.builder()
                .userId(UUID.randomUUID()).resumeVersion(name)
                .applications(applications).interviews(interviews).offers(offers)
                .interviewRate(BigDecimal.valueOf(applications == 0 ? 0 : interviews * 100.0 / applications))
                .offerRate(BigDecimal.valueOf(applications == 0 ? 0 : offers * 100.0 / applications))
                .bestVersion(best).computedAt(Instant.now())
                .build();
    }

    private static SuccessPattern pattern(String dimension, String key, int applications,
                                          int interviews, int offers, double rate) {
        return SuccessPattern.builder()
                .userId(UUID.randomUUID()).dimension(dimension).dimensionKey(key)
                .applications(applications).interviews(interviews).offers(offers)
                .successRate(BigDecimal.valueOf(rate)).computedAt(Instant.now())
                .build();
    }

    // ── gating ──

    @Test
    void disabledReturnsAnEmptySnapshotAndTouchesNoRepository() {
        ProductionOptimizationSnapshot snapshot = service(false).getSnapshot(userId);

        assertThat(snapshot.isEmpty()).isTrue();
        assertThat(snapshot.notes()).anySatisfy(n -> assertThat(n).contains("disabled"));
        verify(resumeLearning, never()).findByUserIdOrderByOfferRateDesc(any());
        verify(successPatterns, never()).findByUserIdAndDimensionOrderBySuccessRateDesc(any(), anyString());
    }

    @Test
    void aUserWithNoHistoryGetsTheHonestEmptyStatement() {
        ProductionOptimizationSnapshot snapshot = service(true).getSnapshot(userId);

        assertThat(snapshot.isEmpty()).isTrue();
        assertThat(snapshot.notes()).anySatisfy(n -> assertThat(n).contains("No verified production evidence"));
    }

    // ── the evidence floor ──

    @Test
    void aResumeVersionBelowTheEvidenceFloorIsNeverRecommended() {
        // Three applications and one interview is a 33% rate. Recommending on that is exactly the
        // fabrication this layer exists to prevent.
        when(resumeLearning.findByUserIdOrderByOfferRateDesc(userId))
                .thenReturn(List.of(version("v1", 3, 1, 0, true)));

        var resume = service(true).getSnapshot(userId).resume();

        assertThat(resume).isNotNull();
        assertThat(resume.recommendedVersion()).isNull();
        assertThat(resume.reason()).contains("below the");
        // The real numbers are still reported — withholding the recommendation is not withholding data.
        assertThat(resume.applications()).isEqualTo(3);
        assertThat(resume.evidence().isActionable()).isFalse();
    }

    @Test
    void dimensionEntriesBelowTheFloorAreDroppedNotShownWeakly() {
        when(successPatterns.findByUserIdAndDimensionOrderBySuccessRateDesc(userId, SuccessPattern.DIM_LOCATION))
                .thenReturn(List.of(
                        pattern(SuccessPattern.DIM_LOCATION, "Iceland", 2, 2, 0, 100.0),
                        pattern(SuccessPattern.DIM_LOCATION, "Germany", 40, 12, 3, 30.0)));

        var countries = service(true).getSnapshot(userId).countries();

        // A 100% rate off two applications must not head the list.
        assertThat(countries).hasSize(1);
        assertThat(countries.get(0).key()).isEqualTo("Germany");
    }

    @Test
    void aWellEvidencedResumeVersionIsRecommendedWithItsRealCounts() {
        when(resumeLearning.findByUserIdOrderByOfferRateDesc(userId))
                .thenReturn(List.of(version("v8", 312, 92, 18, true), version("v7", 120, 20, 2, false)));

        var resume = service(true).getSnapshot(userId).resume();

        assertThat(resume.recommendedVersion()).isEqualTo("v8");
        assertThat(resume.versionsCompared()).isEqualTo(2);
        assertThat(resume.evidence().level()).isEqualTo(Evidence.Level.HIGH);
        assertThat(resume.evidence().cite()).contains("312").contains("92 interviews").contains("18 offers");
        assertThat(resume.evidence().source()).isEqualTo("ResumeLearningService");
    }

    /**
     * {@code ResumeLearningService} decided the tie-break rule. Re-ranking here would be a second
     * definition of "best" that can drift from the first.
     */
    @Test
    void theBestVersionFlagIsHonouredRatherThanReRanked() {
        when(resumeLearning.findByUserIdOrderByOfferRateDesc(userId))
                .thenReturn(List.of(version("v1", 100, 10, 5, false), version("v2", 100, 40, 5, true)));

        assertThat(service(true).getSnapshot(userId).resume().recommendedVersion()).isEqualTo("v2");
    }

    // ── orchestration discipline ──

    @Test
    void eachDimensionIsQueriedExactlyOnceWithNoNPlusOne() {
        when(successPatterns.findByUserIdAndDimensionOrderBySuccessRateDesc(any(), anyString()))
                .thenReturn(List.of(pattern("X", "a", 40, 10, 2, 25.0), pattern("X", "b", 30, 5, 1, 16.0)));

        service(true).getSnapshot(userId);

        verify(resumeLearning, times(1)).findByUserIdOrderByOfferRateDesc(userId);
        verify(successPatterns, times(1))
                .findByUserIdAndDimensionOrderBySuccessRateDesc(userId, SuccessPattern.DIM_LOCATION);
        verify(successPatterns, times(1))
                .findByUserIdAndDimensionOrderBySuccessRateDesc(userId, SuccessPattern.DIM_COMPANY);
        verify(successPatterns, times(1))
                .findByUserIdAndDimensionOrderBySuccessRateDesc(userId, SuccessPattern.DIM_SKILL);
    }

    @Test
    void oneFailingSourceDegradesOnlyItsOwnSection() {
        when(resumeLearning.findByUserIdOrderByOfferRateDesc(userId))
                .thenThrow(new IllegalStateException("db down"));
        when(successPatterns.findByUserIdAndDimensionOrderBySuccessRateDesc(userId, SuccessPattern.DIM_LOCATION))
                .thenReturn(List.of(pattern(SuccessPattern.DIM_LOCATION, "Germany", 40, 12, 3, 30.0)));

        ProductionOptimizationSnapshot snapshot = service(true).getSnapshot(userId);

        assertThat(snapshot.resume()).isNull();
        assertThat(snapshot.countries()).hasSize(1);
        assertThat(snapshot.notes()).anySatisfy(n -> assertThat(n).contains("resume unavailable"));
    }

    @Test
    void aTopListIsCappedSoItStaysASignal() {
        List<SuccessPattern> many = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) many.add(pattern(SuccessPattern.DIM_SKILL, "skill" + i, 40, 10, 2, 30.0 - i));
        when(successPatterns.findByUserIdAndDimensionOrderBySuccessRateDesc(userId, SuccessPattern.DIM_SKILL))
                .thenReturn(many);

        assertThat(service(true).getSnapshot(userId).skills()).hasSize(5);
    }

    // ── ATS ──

    @Test
    void atsIntelligenceCarriesTheCaveatThatItMeasuresAutomationNotOutcomes() {
        when(validationHistory.isEnabled()).thenReturn(true);
        when(validationHistory.platformReadiness()).thenReturn(List.of(
                new ValidationHistoryService.PlatformReadiness("GREENHOUSE", 98, true, 12, Instant.now()),
                new ValidationHistoryService.PlatformReadiness("WORKDAY", 62, false, 5, Instant.now())));

        var ats = service(true).getSnapshot(userId).ats();

        assertThat(ats.bestPlatform()).isEqualTo("GREENHOUSE");
        assertThat(ats.weakestPlatform()).isEqualTo("WORKDAY");
        assertThat(ats.readyPlatforms()).containsExactly("GREENHOUSE");
        // The costly misreading is "Greenhouse gets me more interviews". No table supports that.
        assertThat(ats.caveat()).contains("does not indicate which ATS produces more interviews");
    }

    @Test
    void atsIntelligenceIsAbsentWhenValidationHistoryIsOff() {
        when(validationHistory.isEnabled()).thenReturn(false);
        when(resumeLearning.findByUserIdOrderByOfferRateDesc(userId))
                .thenReturn(List.of(version("v8", 312, 92, 18, true)));

        assertThat(service(true).getSnapshot(userId).ats()).isNull();
    }

    // ── evidence bands ──

    @Test
    void confidenceBandsFollowSampleSizeAndNothingElse() {
        assertThat(Evidence.levelFor(0)).isEqualTo(Evidence.Level.INSUFFICIENT);
        assertThat(Evidence.levelFor(4)).isEqualTo(Evidence.Level.INSUFFICIENT);
        assertThat(Evidence.levelFor(5)).isEqualTo(Evidence.Level.LOW);
        assertThat(Evidence.levelFor(19)).isEqualTo(Evidence.Level.LOW);
        assertThat(Evidence.levelFor(20)).isEqualTo(Evidence.Level.MEDIUM);
        assertThat(Evidence.levelFor(50)).isEqualTo(Evidence.Level.HIGH);
    }

    @Test
    void aCitationIsAlwaysCheckable() {
        Evidence evidence = Evidence.of("ResumeLearningService", 312,
                new java.util.LinkedHashMap<>(java.util.Map.of("interviews", 92)), Instant.now());
        assertThat(evidence.cite()).contains("312 observations").contains("92 interviews")
                .contains("ResumeLearningService");
    }

    @Test
    void aNullUserIsHandledWithoutThrowing() {
        assertThat(service(true).getSnapshot(null).isEmpty()).isTrue();
    }
}
