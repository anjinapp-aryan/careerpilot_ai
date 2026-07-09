package ai.careerpilot.companyintel;

import ai.careerpilot.companyintel.analyzer.*;
import ai.careerpilot.domain.CompanyRelationship;
import ai.careerpilot.domain.CompanyTimelineEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Analyzers are deterministic and honest: nothing observed ⇒ empty, never fabricated filler. */
class CompanyAnalyzersTest {

    private static CompanyRelationship edge(String type, String target, int weight) {
        return CompanyRelationship.builder().relationType(type).target(target).weight(weight).build();
    }

    private static CompanyTimelineEvent event(String type, Instant at) {
        CompanyTimelineEvent e = CompanyTimelineEvent.builder().eventType(type).build();
        e.setOccurredAt(at);
        return e;
    }

    @Test
    void technologyAnalyzerRanksByWeightAndEmptiesWithoutEdges() {
        assertTrue(new TechnologyAnalyzer().analyze(List.of()).isEmpty());
        Optional<CompanyInsight> insight = new TechnologyAnalyzer().analyze(List.of(
                edge(CompanyRelationship.TYPE_TECHNOLOGY, "kafka", 5),
                edge(CompanyRelationship.TYPE_SKILL, "java", 9)));
        assertTrue(insight.isPresent());
        assertTrue(insight.get().detail().indexOf("java") < insight.get().detail().indexOf("kafka"));
    }

    @Test
    void hiringPatternAnalyzerCountsOutcomes() {
        Optional<CompanyInsight> insight = new HiringPatternAnalyzer().analyze(
                List.of(edge(CompanyRelationship.TYPE_ROLE, "backend engineer", 2)),
                List.of(event("APPLICATION_SUBMITTED", Instant.now()), event("OFFER_RECEIVED", Instant.now())));
        assertTrue(insight.isPresent());
        assertTrue(insight.get().detail().contains("1 applications"));
        assertTrue(insight.get().detail().contains("1 offers"));
    }

    @Test
    void cultureAnalyzerEmptyWithoutCultureKnowledge() {
        assertTrue(new CultureAnalyzer().analyze(Map.of()).isEmpty());
        assertTrue(new CultureAnalyzer().analyze(Map.of("culture", "flat hierarchy")).isPresent());
    }

    @Test
    void growthAnalyzerReadsRoleBreadth() {
        Optional<CompanyInsight> insight = new GrowthAnalyzer().analyze(Map.of(), List.of(
                edge(CompanyRelationship.TYPE_ROLE, "a", 1),
                edge(CompanyRelationship.TYPE_ROLE, "b", 1),
                edge(CompanyRelationship.TYPE_ROLE, "c", 1)));
        assertTrue(insight.isPresent());
        assertTrue(insight.get().headline().contains("3"));
    }

    @Test
    void skillDemandAnalyzerShowsObservationCounts() {
        Optional<CompanyInsight> insight = new SkillDemandAnalyzer().analyze(
                List.of(edge(CompanyRelationship.TYPE_SKILL, "java", 4)));
        assertTrue(insight.isPresent());
        assertTrue(insight.get().detail().contains("java (x4)"));
    }

    @Test
    void trendAnalyzerComparesLast30DaysToPrior30() {
        Instant now = Instant.now();
        Optional<CompanyInsight> growing = new CompanyTrendAnalyzer().analyze(List.of(
                event("DISCOVERED", now.minus(2, ChronoUnit.DAYS)),
                event("RECOMMENDED", now.minus(3, ChronoUnit.DAYS)),
                event("DISCOVERED", now.minus(45, ChronoUnit.DAYS))));
        assertEquals("GROWING", growing.orElseThrow().headline());
        assertTrue(new CompanyTrendAnalyzer().analyze(List.of()).isEmpty());
    }
}
