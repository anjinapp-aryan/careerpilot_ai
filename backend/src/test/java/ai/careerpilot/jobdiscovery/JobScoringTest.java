package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.JobScoring.CandidateContext;
import ai.careerpilot.jobdiscovery.JobScoring.PreferenceContext;
import ai.careerpilot.jobdiscovery.JobScoring.ScoreResultV2;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioral guards for the rebuilt recommendation scoring. These are the exact failure modes the
 * Job-engine audit targeted: industry leakage, sparse-JD skill inflation, junior roles ranking high
 * for senior candidates, role false-negatives, and country mis-inference. Pure functions — no
 * Spring context needed.
 */
class JobScoringTest {

    private final JobTaxonomy taxonomy = new JobTaxonomy();
    private final JobScoring scoring = new JobScoring(taxonomy);

    /** A 12-yr Java/Spring/Cloud engineer targeting architect/backend roles. */
    private CandidateContext seniorJavaCandidate() {
        return new CandidateContext(
                List.of("java", "spring boot", "microservices", "aws", "terraform", "react", "spark"),
                "Java Architect", List.of(), 12, 80);
    }

    private Job job(String title, String description) {
        return Job.builder().title(title).company("Acme").description(description).build();
    }

    @Test
    void seniorBackendRoleScoresHighAndPassesGate() {
        Job j = job("Senior Java Engineer",
                "We need strong Java, Spring Boot, microservices and AWS experience. 8+ years.");
        ScoreResultV2 r = scoring.scoreV2(j, seniorJavaCandidate(), PreferenceContext.empty());

        assertTrue(r.matchScore() >= 80, "relevant senior role should score high, was " + r.matchScore());
        assertTrue(r.matchedSkillFamilyCount() >= 3, "should match >=3 skill families");
        assertTrue(r.matchedRoleCount() >= 1, "Java Architect vs Java Engineer should share a role family");
    }

    @Test
    void sparseJdMentioningOneSkillDoesNotReach100() {
        // The old bug: a JD naming a single skill the candidate has scored 100% on skills.
        Job j = job("Operations Coordinator", "Comfortable with AWS billing dashboards.");
        ScoreResultV2 r = scoring.scoreV2(j, seniorJavaCandidate(), PreferenceContext.empty());

        // One matched family over the denominator floor of 3 → ~33, never 100.
        assertEquals(33, r.breakdown().skills(), "single-skill JD must be capped by the denominator floor");
        assertTrue(r.matchedSkillFamilyCount() < 3, "should not clear the 3-skill gate");
    }

    @Test
    void juniorRoleIsDeprioritisedForSeniorCandidate() {
        Job junior = job("Junior Java Developer", "Entry-level role. Java basics.");
        ScoreResultV2 r = scoring.scoreV2(junior, seniorJavaCandidate(), PreferenceContext.empty());
        assertTrue(r.breakdown().experience() <= 30,
                "12-yr candidate on a junior role should score low on experience, was " + r.breakdown().experience());
    }

    @Test
    void industryClassifierSeparatesTechFromNonTech() {
        assertEquals("MARKETING", taxonomy.classifyFamily("Marketing Manager", "Own our campaigns and SEO."));
        assertEquals("SALES", taxonomy.classifyFamily("Account Executive", "Close deals, hit quota."));
        assertEquals("RECRUITER", taxonomy.classifyFamily("Technical Recruiter", "Source engineering talent."));
        // A role noun in the title wins: "Sales Engineer" is a technical pre-sales role.
        assertEquals("TECH", taxonomy.classifyFamily("Sales Engineer", "Demo our API to customers."));
        assertEquals("TECH", taxonomy.classifyFamily("Senior Java Developer", "Spring Boot microservices."));

        assertTrue(taxonomy.isExcludedFamily("MARKETING"));
        assertFalse(taxonomy.isExcludedFamily("TECH"));
        assertFalse(taxonomy.isExcludedFamily(null));
    }

    @Test
    void roleTaxonomyRelatesArchitectAndBackendFamilies() {
        assertTrue(taxonomy.roleFamilies("Java Architect").contains("ARCHITECT"));
        assertTrue(taxonomy.roleFamilies("Java Architect").contains("BACKEND"));
        assertTrue(taxonomy.roleFamilies("Backend Engineer").contains("BACKEND"));
    }

    @Test
    void skillFamiliesCollapseSpellingVariants() {
        assertEquals(taxonomy.skillFamily("springboot"), taxonomy.skillFamily("spring boot"));
        assertEquals(taxonomy.skillFamily("k8s"), taxonomy.skillFamily("kubernetes"));
        assertEquals(taxonomy.skillFamily("micro services"), taxonomy.skillFamily("microservices"));
    }

    @Test
    void countryInferenceResolvesCommonCitiesAndCountries() {
        JobNormalizer normalizer = new JobNormalizer(scoring, new JobEnricher(taxonomy), true);
        assertEquals("Germany", normalizer.inferCountry("Frankfurt"));
        assertEquals("Canada", normalizer.inferCountry("Toronto, Canada"));
        assertEquals("India", normalizer.inferCountry("Bengaluru"));
        assertEquals("United Kingdom", normalizer.inferCountry("Leeds, England"));
        // Unknown location stays null → handled as "international/global", never mislabeled.
        assertNull(normalizer.inferCountry("Worldwide"));
    }
}
