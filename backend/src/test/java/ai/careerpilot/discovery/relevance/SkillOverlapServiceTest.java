package ai.careerpilot.discovery.relevance;

import ai.careerpilot.jobdiscovery.JobTaxonomy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Reproduces the Phase 3B.1 spec's literal skill-overlap examples exactly. */
class SkillOverlapServiceTest {

    private final SkillOverlapService service = new SkillOverlapService(new JobTaxonomy());

    @Test
    void strongOverlapScoresInEightyToHundredBand() {
        List<String> candidate = List.of("Java", "Spring Boot", "AWS", "Kafka", "Microservices", "Docker", "Kubernetes");
        List<String> job = List.of("Java", "Spring", "AWS", "Kafka");

        int score = service.overlapPercent(candidate, job);
        assertTrue(score >= 80 && score <= 100, "expected 80-100, was " + score);
    }

    @Test
    void noOverlapScoresZero() {
        List<String> candidate = List.of("Java", "Spring Boot", "AWS", "Kafka", "Microservices", "Docker", "Kubernetes");
        List<String> job = List.of("Photoshop", "Marketing", "Customer Service");

        assertEquals(0, service.overlapPercent(candidate, job));
    }

    @Test
    void springVariantsAreNormalizedAsOneFamily() {
        // "Spring Boot" (candidate) and "Spring" (job) must match — same skill family.
        int score = service.overlapPercent(List.of("Spring Boot"), List.of("Spring"));
        assertEquals(100, score);
    }

    @Test
    void emptyJobSkillsScoresZero() {
        assertEquals(0, service.overlapPercent(List.of("Java"), List.<String>of()));
    }

    @Test
    void csvOverloadMatchesListOverload() {
        List<String> candidate = List.of("Java", "AWS");
        assertEquals(service.overlapPercent(candidate, List.of("Java", "AWS")),
                service.overlapPercent(candidate, "Java, AWS"));
    }
}
