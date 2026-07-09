package ai.careerpilot.companyintel;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pure merge rules — blank never erases, identical is a no-op, changes are reported exactly. */
class KnowledgeAggregatorTest {

    private final KnowledgeAggregator aggregator = new KnowledgeAggregator();

    @Test
    void mergesNewSectionsIntoEmptyKnowledge() {
        var result = aggregator.merge(null, Map.of(KnowledgeSection.CULTURE, "collaborative"));
        assertTrue(result.changed());
        assertEquals("collaborative", result.merged().get("culture"));
        assertEquals(java.util.List.of("culture"), result.changedSections());
    }

    @Test
    void blankUpdateNeverErasesKnowledge() {
        String existing = aggregator.write(Map.of("culture", "collaborative"));
        var result = aggregator.merge(existing, Map.of(KnowledgeSection.CULTURE, "   "));
        assertFalse(result.changed());
        assertEquals("collaborative", result.merged().get("culture"));
    }

    @Test
    void identicalContentIsNoOp() {
        String existing = aggregator.write(Map.of("culture", "collaborative"));
        var result = aggregator.merge(existing, Map.of(KnowledgeSection.CULTURE, "collaborative"));
        assertFalse(result.changed());
    }

    @Test
    void changedContentReplacesAndReports() {
        String existing = aggregator.write(Map.of("culture", "old"));
        var result = aggregator.merge(existing, Map.of(KnowledgeSection.CULTURE, "new"));
        assertTrue(result.changed());
        assertEquals("new", result.merged().get("culture"));
    }

    @Test
    void unrelatedSectionsSurviveMerge() {
        String existing = aggregator.write(Map.of("profile", "what they do"));
        var result = aggregator.merge(existing, Map.of(KnowledgeSection.SKILL_DEMAND, "java, sql"));
        assertEquals("what they do", result.merged().get("profile"));
        assertEquals("java, sql", result.merged().get("skillDemand"));
    }

    @Test
    void oversizedSectionIsCapped() {
        var result = aggregator.merge(null, Map.of(KnowledgeSection.PROFILE, "x".repeat(20_000)));
        assertTrue(result.merged().get("profile").length() <= 8000);
    }

    @Test
    void corruptJsonParsesAsEmpty() {
        assertTrue(aggregator.parse("not-json{{{").isEmpty());
        assertTrue(aggregator.parse(null).isEmpty());
    }

    @Test
    void roundTripsThroughJson() {
        Map<String, String> sections = Map.of("culture", "a", "profile", "b");
        assertEquals(sections, aggregator.parse(aggregator.write(sections)));
    }
}
