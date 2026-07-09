package ai.careerpilot.discovery.relevance;

import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.JobTaxonomy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end eligibility verdicts, wiring real (non-mocked) collaborators — all pure functions. */
class JobEligibilityEngineTest {

    private final RoleFamilyResolver roleFamilyResolver = new RoleFamilyResolver(
            "java developer,senior java developer,java lead,java architect,backend engineer,"
                    + "technical lead,principal engineer,solution architect,software engineer,"
                    + "backend developer,java engineer",
            "data engineer,spark engineer,big data engineer,data engineering,etl engineer,analytics engineer",
            "devops engineer,platform engineer,sre,site reliability engineer,infrastructure engineer,cloud engineer",
            "react developer,ui engineer,frontend engineer,frontend developer,front end developer,angular developer",
            "Media,Hospitality,Customer Service,Construction,Sales,Marketing,HR,Creative,BIM");

    private final JobEligibilityEngine engine = new JobEligibilityEngine(
            new RoleFamilyService(roleFamilyResolver),
            new ExperienceEligibilityService(3),
            new SkillOverlapService(new JobTaxonomy()),
            new DomainPreferenceService(new JobTaxonomy(),
                    "Media,Hospitality,Customer Service,Construction,Sales,Marketing,HR,Creative,BIM"));

    private static Job job(String title, String description, Integer requiredExperience, String skills) {
        return Job.builder().title(title).description(description).company("Acme")
                .requiredExperience(requiredExperience).skills(skills).build();
    }

    private static RelevanceCandidateContext seniorJavaCandidate() {
        return new RelevanceCandidateContext("Java Architect",
                List.of("Java", "Spring Boot", "AWS", "Kafka", "Microservices", "Docker", "Kubernetes"),
                12, List.of());
    }

    @Test
    void strongMatchIsEligible() {
        Job j = job("Senior Java Developer", "Java, Spring Boot, AWS, Kafka experience required.", 10,
                "Java,Spring,AWS,Kafka");
        var result = engine.evaluate(j, seniorJavaCandidate());
        assertEquals(JobEligibilityEngine.Verdict.ELIGIBLE, result.verdict());
        assertTrue(result.eligible());
    }

    @Test
    void excludedRoleFamilyIsRejectedOnRole() {
        Job j = job("Media Manager", "", null, "Photoshop");
        var result = engine.evaluate(j, seniorJavaCandidate());
        assertEquals(JobEligibilityEngine.Verdict.REJECTED_ROLE, result.verdict());
    }

    @Test
    void tooJuniorRoleIsRejectedOnExperience() {
        Job j = job("Java Developer", "Entry level Java role.", 2, "Java,Spring");
        var result = engine.evaluate(j, seniorJavaCandidate());
        assertEquals(JobEligibilityEngine.Verdict.REJECTED_EXPERIENCE, result.verdict());
    }

    @Test
    void noSkillOverlapIsRejectedOnSkills() {
        Job j = job("Senior Java Developer", "We need Photoshop and Illustrator skills.", 10, "Photoshop,Illustrator");
        var result = engine.evaluate(j, seniorJavaCandidate());
        assertEquals(JobEligibilityEngine.Verdict.REJECTED_SKILLS, result.verdict());
    }

    @Test
    void excludedDomainIsRejectedOnDomain() {
        // "Payroll Coordinator" carries no role-family signal (classifies OTHER, not EXCLUDED, in
        // RoleFamilyResolver's narrower 4-family + 9-keyword taxonomy) and has full skill/experience
        // fit, but JobTaxonomy classifies it FINANCE (an excluded family) — isolating domain as the
        // sole failing gate.
        Job j = job("Payroll Coordinator", "Manage payroll systems using Java-based tools.", 10, "Java,Spring");
        var result = engine.evaluate(j, seniorJavaCandidate());
        assertEquals(JobEligibilityEngine.Verdict.REJECTED_DOMAIN, result.verdict());
    }
}
