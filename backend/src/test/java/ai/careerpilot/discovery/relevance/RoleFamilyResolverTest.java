package ai.careerpilot.discovery.relevance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Role-family classification: the four named tech families, the excluded (non-tech) category,
 * and the OTHER fallback for a recognizable-but-unnamed technical role. Pure function — no Spring
 * context needed. Constructed with the resolver's built-in default keyword lists (empty string ⇒
 * the {@code @Value} default applies only under Spring, so tests pass the literal defaults here).
 */
class RoleFamilyResolverTest {

    private final RoleFamilyResolver resolver = new RoleFamilyResolver(
            "java developer,senior java developer,java lead,java architect,backend engineer,"
                    + "technical lead,principal engineer,solution architect,software engineer,"
                    + "backend developer,java engineer",
            "data engineer,spark engineer,big data engineer,data engineering,etl engineer,analytics engineer",
            "devops engineer,platform engineer,sre,site reliability engineer,infrastructure engineer,cloud engineer",
            "react developer,ui engineer,frontend engineer,frontend developer,front end developer,angular developer",
            "Media,Hospitality,Customer Service,Construction,Sales,Marketing,HR,Creative,BIM");

    @Test
    void classifiesJavaBackendTitles() {
        assertEquals(RoleFamily.JAVA_BACKEND, resolver.resolve("Senior Java Developer"));
        assertEquals(RoleFamily.JAVA_BACKEND, resolver.resolve("Java Architect"));
        assertEquals(RoleFamily.JAVA_BACKEND, resolver.resolve("Solution Architect"));
    }

    @Test
    void classifiesDataEngineeringTitles() {
        assertEquals(RoleFamily.DATA_ENGINEERING, resolver.resolve("Data Engineer"));
        assertEquals(RoleFamily.DATA_ENGINEERING, resolver.resolve("Spark Engineer"));
    }

    @Test
    void classifiesDevOpsTitles() {
        assertEquals(RoleFamily.DEVOPS, resolver.resolve("DevOps Engineer"));
        assertEquals(RoleFamily.DEVOPS, resolver.resolve("Platform Engineer"));
        assertEquals(RoleFamily.DEVOPS, resolver.resolve("SRE"));
    }

    @Test
    void classifiesFrontendTitles() {
        assertEquals(RoleFamily.FRONTEND, resolver.resolve("React Developer"));
        assertEquals(RoleFamily.FRONTEND, resolver.resolve("UI Engineer"));
    }

    @Test
    void classifiesExcludedTitles() {
        assertEquals(RoleFamily.EXCLUDED, resolver.resolve("Media Manager"));
        assertEquals(RoleFamily.EXCLUDED, resolver.resolve("Hospitality Coach"));
        assertEquals(RoleFamily.EXCLUDED, resolver.resolve("BIM Engineer"));
        assertEquals(RoleFamily.EXCLUDED, resolver.resolve("Customer Service Representative"));
    }

    @Test
    void unrecognizedTechTitleFallsBackToOther() {
        assertEquals(RoleFamily.OTHER, resolver.resolve("QA Automation Specialist"));
    }

    @Test
    void excludedKeywordInTitleWinsOverAnyPartialTechOverlap() {
        // "BIM Engineer" contains "engineer" but must still classify EXCLUDED (BIM is a hard exclude).
        assertEquals(RoleFamily.EXCLUDED, resolver.resolve("BIM Engineer", "construction modeling"));
    }
}
