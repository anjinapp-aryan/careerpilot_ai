package ai.careerpilot.discovery.relevance;

import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.CandidateSignalResolver;
import ai.careerpilot.jobdiscovery.JobScoring;
import ai.careerpilot.jobdiscovery.JobTaxonomy;
import ai.careerpilot.repo.CandidateProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Explainability (Step 10): disabled-by-default gating, resolved context, and reason strings. */
class CareerRelevanceEvaluatorTest {

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

    private static Job javaJob() {
        return Job.builder().title("Senior Java Developer")
                .description("Java, Spring Boot, AWS, Kafka required. 8+ years.")
                .company("Acme").requiredExperience(10).skills("Java,Spring,AWS,Kafka").build();
    }

    private CandidateSignalResolver signalResolverReturning(String targetRole, List<String> skills, Integer years) {
        CandidateSignalResolver resolver = mock(CandidateSignalResolver.class);
        when(resolver.resolve(any())).thenReturn(Optional.of(new CandidateSignalResolver.CandidateMatchSignals(
                skills, targetRole, List.of(), years, null, JobScoring.PreferenceContext.empty(), List.of(), null, "PROFILE")));
        return resolver;
    }

    private static final CareerThresholdPolicy DEFAULT_POLICY =
            new CareerThresholdPolicy(false, 85, 60, 60, 60);

    @Test
    void explainReturnsEmptyWhenExplainabilityDisabled() {
        CareerRelevanceEvaluator evaluator = new CareerRelevanceEvaluator(
                signalResolverReturning("Java Architect", List.of("Java", "Spring Boot"), 12),
                mock(CandidateProfileRepository.class), engine, DEFAULT_POLICY, true, false);

        assertTrue(evaluator.explain(UUID.randomUUID(), javaJob()).isEmpty());
    }

    @Test
    void explainReturnsFullPayloadWhenEnabled() {
        CandidateProfileRepository profiles = mock(CandidateProfileRepository.class);
        when(profiles.findByUserId(any())).thenReturn(Optional.empty());

        CareerRelevanceEvaluator evaluator = new CareerRelevanceEvaluator(
                signalResolverReturning("Java Architect",
                        List.of("Java", "Spring Boot", "AWS", "Kafka", "Microservices", "Docker", "Kubernetes"), 12),
                profiles, engine, DEFAULT_POLICY, true, true);

        var explanation = evaluator.explain(UUID.randomUUID(), javaJob());
        assertTrue(explanation.isPresent());
        var e = explanation.get();
        assertTrue(e.relevanceScore() >= 80, "expected a strong match, was " + e.relevanceScore());
        assertTrue(e.roleMatch());
        assertTrue(e.experienceFit());
        assertTrue(e.domainFit());
        assertEquals(4, e.reasons().size());
        assertTrue(e.reasons().get(0).contains("Java Architect"));
    }

    @Test
    void resolveContextFallsBackToEmptyWhenNoSignals() {
        CandidateSignalResolver resolver = mock(CandidateSignalResolver.class);
        when(resolver.resolve(any())).thenReturn(Optional.empty());
        CandidateProfileRepository profiles = mock(CandidateProfileRepository.class);
        when(profiles.findByUserId(any())).thenReturn(Optional.empty());

        CareerRelevanceEvaluator evaluator = new CareerRelevanceEvaluator(resolver, profiles, engine, DEFAULT_POLICY, true, true);
        RelevanceCandidateContext ctx = evaluator.resolveContext(UUID.randomUUID());

        assertNull(ctx.targetRole());
        assertTrue(ctx.skills().isEmpty());
        assertTrue(ctx.preferredDomains().isEmpty());
    }

    @Test
    void evaluateForScopeUsesLegacyCutoffWhenSoftThresholdsDisabled() {
        CareerThresholdPolicy legacyPolicy = new CareerThresholdPolicy(false, 85, 60, 60, 60);
        CareerRelevanceEvaluator evaluator = new CareerRelevanceEvaluator(
                signalResolverReturning("Java Architect", List.of("Java", "Spring"), 12),
                mock(CandidateProfileRepository.class), engine, legacyPolicy, true, true);

        // javaJob() is a strong match (>= 80), so it clears both the legacy 70 cutoff and any soft band.
        CareerRelevanceResult result = evaluator.evaluateForScope(javaJob(),
                evaluator.resolveContext(UUID.randomUUID()), "domestic");

        assertTrue(result.visible());
        assertEquals(CareerMatchStrength.fromScore(result.score()), result.strength());
        assertEquals(4, result.reasons().size());
    }

    @Test
    void evaluateForScopeUsesConfiguredBandWhenSoftThresholdsEnabled() {
        CareerThresholdPolicy softPolicy = new CareerThresholdPolicy(true, 85, 95, 95, 60);
        CareerRelevanceEvaluator evaluator = new CareerRelevanceEvaluator(
                signalResolverReturning("Java Architect", List.of("Java", "Spring"), 12),
                mock(CandidateProfileRepository.class), engine, softPolicy, true, true);

        // Domestic threshold raised to 95 — the strong-but-not-perfect javaJob() match must now be hidden.
        CareerRelevanceResult result = evaluator.evaluateForScope(javaJob(),
                evaluator.resolveContext(UUID.randomUUID()), "domestic");

        assertFalse(result.visible());
    }

    @Test
    void isEnabledReflectsMasterFlag() {
        CareerRelevanceEvaluator evaluator = new CareerRelevanceEvaluator(
                mock(CandidateSignalResolver.class), mock(CandidateProfileRepository.class), engine, DEFAULT_POLICY, false, false);
        assertFalse(evaluator.isEnabled());
    }
}
