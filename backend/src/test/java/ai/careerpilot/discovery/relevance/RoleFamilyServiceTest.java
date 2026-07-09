package ai.careerpilot.discovery.relevance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Business-logic layer over {@link RoleFamilyResolver}: eligibility + similarity scoring. */
class RoleFamilyServiceTest {

    private final RoleFamilyService service = new RoleFamilyService(new RoleFamilyResolver(
            "java developer,senior java developer,java lead,java architect,backend engineer,"
                    + "technical lead,principal engineer,solution architect,software engineer,"
                    + "backend developer,java engineer",
            "data engineer,spark engineer,big data engineer,data engineering,etl engineer,analytics engineer",
            "devops engineer,platform engineer,sre,site reliability engineer,infrastructure engineer,cloud engineer",
            "react developer,ui engineer,frontend engineer,frontend developer,front end developer,angular developer",
            "Media,Hospitality,Customer Service,Construction,Sales,Marketing,HR,Creative,BIM"));

    @Test
    void sameFamilyIsEligibleWithFullSimilarity() {
        var r = service.evaluate("Java Architect", "Senior Java Developer", "Java, Spring, AWS");
        assertTrue(r.eligible());
        assertEquals(100, r.similarity());
    }

    @Test
    void differentNamedFamiliesAreNotEligible() {
        var r = service.evaluate("React Developer", "DevOps Engineer", "Kubernetes, Terraform");
        assertFalse(r.eligible());
        assertEquals(0, r.similarity());
    }

    @Test
    void excludedJobFamilyIsNeverEligible() {
        var r = service.evaluate("Java Architect", "Media Manager", "");
        assertFalse(r.eligible());
        assertEquals(0, r.similarity());
    }

    @Test
    void unknownCandidateFamilyIsEligibleButUnscored() {
        var r = service.evaluate("Career Changer", "Senior Java Developer", "Java");
        assertTrue(r.eligible());
        assertEquals(50, r.similarity());
    }

    @Test
    void unnamedTechFamilyJobIsEligibleWithModestSimilarity() {
        var r = service.evaluate("Java Architect", "QA Automation Specialist", "Selenium");
        assertTrue(r.eligible());
        assertEquals(40, r.similarity());
    }
}
