package ai.careerpilot.companyintel;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Pure weighted-Jaccard similarity over graph edges. */
class CompanySimilarityServiceTest {

    @Test
    void identicalEdgeSetsAreFullySimilar() {
        Map<String, Set<String>> edges = Map.of(
                "TECHNOLOGY", Set.of("java", "kafka"),
                "INDUSTRY", Set.of("tech"));
        assertEquals(100, CompanySimilarityService.similarity(edges, edges));
    }

    @Test
    void disjointEdgeSetsAreDissimilar() {
        assertEquals(0, CompanySimilarityService.similarity(
                Map.of("TECHNOLOGY", Set.of("java")),
                Map.of("TECHNOLOGY", Set.of("cobol"))));
    }

    @Test
    void partialOverlapLandsBetween() {
        int sim = CompanySimilarityService.similarity(
                Map.of("TECHNOLOGY", Set.of("java", "kafka")),
                Map.of("TECHNOLOGY", Set.of("java", "rust")));
        assertTrue(sim > 0 && sim < 100, "got " + sim);
    }

    @Test
    void emptyGraphsScoreZero() {
        assertEquals(0, CompanySimilarityService.similarity(Map.of(), Map.of()));
    }

    @Test
    void unknownEdgeTypesAreIgnored() {
        assertEquals(0, CompanySimilarityService.similarity(
                Map.of("SALARY_BAND", Set.of("60K_100K")),
                Map.of("SALARY_BAND", Set.of("60K_100K"))));
    }

    @Test
    void deterministicAndSymmetric() {
        Map<String, Set<String>> a = Map.of("SKILL", Set.of("java", "sql"));
        Map<String, Set<String>> b = Map.of("SKILL", Set.of("java"));
        assertEquals(CompanySimilarityService.similarity(a, b), CompanySimilarityService.similarity(b, a));
    }
}
