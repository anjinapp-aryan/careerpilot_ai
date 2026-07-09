package ai.careerpilot.companyintel;

import ai.careerpilot.companyintel.CompanyScoringService.CompanyStats;
import ai.careerpilot.companyintel.CompanyScoringService.ScoreSet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic, clamped, explainable scoring over observed facts only. */
class CompanyScoringServiceTest {

    private final CompanyScoringService scoring = new CompanyScoringService();

    @Test
    void emptyStatsYieldNeutralishClampedScores() {
        ScoreSet result = scoring.score(CompanyStats.empty());
        result.scores().forEach((key, value) -> {
            assertTrue(value >= 0 && value <= 100, key + " out of range: " + value);
            assertNotNull(result.explanations().get(key), key + " must be explained");
        });
        assertEquals(50, result.scores().get("resumeCompatibility")); // no signals → neutral
        assertEquals(50, result.scores().get("salaryPotential"));
        assertEquals(50, result.scores().get("cultureMatch"));
    }

    @Test
    void deterministicForIdenticalInput() {
        CompanyStats stats = new CompanyStats(Map.of("culture", "x"), Map.of("SKILL", 4L),
                2, 1, 1, 1, Set.of("java", "kafka"), List.of("java"));
        assertEquals(scoring.score(stats).scores(), scoring.score(stats).scores());
    }

    @Test
    void offersRaiseHiringProbabilityRejectionsLowerIt() {
        CompanyStats offers = new CompanyStats(Map.of(), Map.of(), 2, 2, 2, 0, Set.of(), List.of());
        CompanyStats rejections = new CompanyStats(Map.of(), Map.of(), 2, 0, 0, 3, Set.of(), List.of());
        assertTrue(scoring.score(offers).scores().get("hiringProbability")
                > scoring.score(rejections).scores().get("hiringProbability"));
    }

    @Test
    void knowledgeDepthRaisesQuality() {
        CompanyStats rich = new CompanyStats(
                Map.of("culture", "a", "profile", "b", "skillDemand", "c", "growthSignals", "d"),
                Map.of("TECHNOLOGY", 10L), 0, 0, 0, 0, Set.of(), List.of());
        assertTrue(scoring.score(rich).scores().get("qualityScore")
                > scoring.score(CompanyStats.empty()).scores().get("qualityScore"));
    }

    @Test
    void interviewsWithoutOffersRaiseDifficulty() {
        CompanyStats hard = new CompanyStats(Map.of(), Map.of(), 3, 3, 0, 3, Set.of(), List.of());
        assertTrue(scoring.score(hard).scores().get("interviewDifficulty") > 50);
    }

    @Test
    void overlapPercentMatchesObservedDemand() {
        assertEquals(-1, CompanyScoringService.overlapPercent(Set.of(), List.of("java")));
        assertEquals(0, CompanyScoringService.overlapPercent(Set.of("java"), List.of()));
        assertEquals(100, CompanyScoringService.overlapPercent(Set.of("java"), List.of("Java")));
        assertEquals(50, CompanyScoringService.overlapPercent(Set.of("java", "rust"), List.of("java")));
    }

    @Test
    void allScoresStayClampedUnderExtremeCounts() {
        CompanyStats extreme = new CompanyStats(Map.of(), Map.of("SKILL", 999L),
                500, 500, 500, 500, Set.of("a"), List.of("a"));
        scoring.score(extreme).scores().values()
                .forEach(v -> assertTrue(v >= 0 && v <= 100, "out of range: " + v));
    }
}
