package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.Job;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The enrichment-aware scoring overloads (Phase 1.5 matching fix): a discovered listing whose raw
 * {@code skills} column is a generic placeholder ("Software Development") and whose description is
 * marketing prose scores below the relevance gate — but the SAME job, scored against its AI-enriched
 * normalized skills, matches a Java candidate strongly. Pins that the override is what unblocks it.
 */
class JobScoringEnrichmentTest {

    private final JobTaxonomy taxonomy = new JobTaxonomy();
    private final JobScoring scoring = new JobScoring(taxonomy);

    private final List<String> candidateSkills = List.of(
            "Java", "AWS", "Spring Boot", "Microservices", "Docker", "Kubernetes", "REST API",
            "PostgreSQL", "JavaScript", "React JS");
    private final String targetRole = "Java Architect Senior Software Engineer Backend Engineer";

    /** A real-world-shaped thin listing: good title, useless skills column, prose description. */
    private Job thinJob() {
        return Job.builder()
                .id(java.util.UUID.randomUUID())
                .title("Senior Full Stack Developer | Java / React (f/m/d)")
                .company("Accurids GmbH")
                .description("Join us and help shape the future of pharmaceutical data! We are building "
                        + "the data backbone behind life-science innovation.")
                .skills("Software Development")     // generic provider placeholder — no tech signal
                .jobFamily("TECH")
                .build();
    }

    private static final String ENRICHED_SKILLS =
            "Java,React,TypeScript,Spring Boot,GraphQL,REST API,PostgreSQL,Redis,Elasticsearch,Docker";

    @Test
    void enrichedSkillsImproveTheRelevanceSignal() {
        // The relevance pre-gate (roleSimilarity) is the documented blocker for thin listings.
        // Feeding the job's real (enriched) skills can only ADD matching terms, so relevance must
        // not decrease — and here it strictly improves over the placeholder "Software Development".
        int raw = scoring.roleSimilarity(thinJob(), candidateSkills, targetRole);
        int enriched = scoring.roleSimilarity(thinJob(), ENRICHED_SKILLS, candidateSkills, targetRole);
        assertTrue(enriched > raw,
                "enriched relevance (" + enriched + ") should beat raw (" + raw + ")");
    }

    @Test
    void enrichedSkillsRaiseMatchedSkillFamilyCount() {
        JobScoring.CandidateContext ctx = new JobScoring.CandidateContext(
                candidateSkills, targetRole, List.of(), 12, null);
        JobScoring.PreferenceContext prefs = JobScoring.PreferenceContext.empty();

        JobScoring.ScoreResultV2 raw = scoring.scoreV2(thinJob(), ctx, prefs);
        JobScoring.ScoreResultV2 enriched = scoring.scoreV2(thinJob(), ENRICHED_SKILLS, ctx, prefs);

        // The strict gate needs >= 3 matched skill families; the placeholder skills column yields
        // almost none, while the enriched skills surface the candidate's real overlapping families.
        assertTrue(enriched.matchedSkillFamilyCount() > raw.matchedSkillFamilyCount(),
                "enriched skill-family count (" + enriched.matchedSkillFamilyCount()
                        + ") should beat raw (" + raw.matchedSkillFamilyCount() + ")");
        assertTrue(enriched.matchedSkillFamilyCount() >= 3,
                "enriched skill-family count should clear the gate, got " + enriched.matchedSkillFamilyCount());
    }

    @Test
    void nullEffectiveSkillsBehavesLikeNoSkillColumn() {
        // The overload must tolerate a null override (job had no enrichment AND no raw skills).
        Job noSkills = Job.builder().id(java.util.UUID.randomUUID())
                .title("Senior Backend Engineer").description("Java Spring Boot").build();
        assertDoesNotThrow(() -> scoring.roleSimilarity(noSkills, null, candidateSkills, targetRole));
        assertDoesNotThrow(() -> scoring.scoreV2(noSkills, null,
                new JobScoring.CandidateContext(candidateSkills, targetRole, List.of(), 12, null),
                JobScoring.PreferenceContext.empty()));
    }
}
