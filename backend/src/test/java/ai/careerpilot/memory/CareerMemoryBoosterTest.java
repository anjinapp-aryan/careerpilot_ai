package ai.careerpilot.memory;

import ai.careerpilot.domain.CareerDecisionMemory;
import ai.careerpilot.domain.Job;
import ai.careerpilot.repo.CareerDecisionMemoryRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 7.15.1/7.15.2 recommendation integration — same discipline as
 * {@code CompanyKnowledgeBooster}: inactive (either flag off) must always be 0, boost must clamp
 * to ±MAX_BOOST, and a repository failure must never propagate (matching never break).
 */
class CareerMemoryBoosterTest {

    private final UUID userId = UUID.randomUUID();

    private Job job(String title, String company, String skills) {
        Job j = new Job();
        j.setTitle(title);
        j.setCompany(company);
        j.setSkills(skills);
        return j;
    }

    @Test
    void inactiveWhenMemoryDisabledEvenIfBoostFlagOn() {
        CareerMemoryBooster booster = new CareerMemoryBooster(mock(CareerDecisionMemoryRepository.class), false, true);
        assertFalse(booster.isActive());
        assertEquals(0, booster.computeBoost(userId, job("Engineer", "Amazon", "java")));
    }

    @Test
    void inactiveWhenBoostFlagDisabledEvenIfMemoryOn() {
        CareerMemoryBooster booster = new CareerMemoryBooster(mock(CareerDecisionMemoryRepository.class), true, false);
        assertFalse(booster.isActive());
        assertEquals(0, booster.computeBoost(userId, job("Engineer", "Amazon", "java")));
    }

    @Test
    void rejectedCompanyPenalizesAndApprovedCompanyBoosts() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        when(repo.findActiveByUserId(any(), any(Instant.class))).thenReturn(List.of(
                appMemory("JOB_REJECTED", "Senior Engineer @ Amazon"),
                appMemory("JOB_APPROVED", "Backend Engineer @ Stripe")));
        CareerMemoryBooster booster = new CareerMemoryBooster(repo, true, true);

        assertTrue(booster.isActive());
        assertEquals(-3, booster.computeBoost(userId, job("Engineer", "Amazon", "java")),
                "case-insensitive substring match on the remembered company");
        assertEquals(2, booster.computeBoost(userId, job("Engineer", "Stripe", "java")));
        assertEquals(0, booster.computeBoost(userId, job("Engineer", "Google", "java")),
                "no memory about this company at all");
    }

    @Test
    void boostClampsToMaxBoostEvenWithManyMatches() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        when(repo.findActiveByUserId(any(), any(Instant.class))).thenReturn(List.of(
                appMemory("JOB_APPROVED", "A @ Google"), appMemory("JOB_APPROVED", "B @ Google"),
                appMemory("JOB_APPROVED", "C @ Google"), appMemory("JOB_APPROVED", "D @ Google")));
        CareerMemoryBooster booster = new CareerMemoryBooster(repo, true, true);
        assertEquals(CareerMemoryBooster.MAX_BOOST, booster.computeBoost(userId, job("Engineer", "Google", "java")));
    }

    @Test
    void positiveTechnologyMemoryBoostsMatchingJob() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        when(repo.findActiveByUserId(any(), any(Instant.class))).thenReturn(List.of(
                techMemory("TECHNOLOGY_POSITIVE", "Kubernetes")));
        CareerMemoryBooster booster = new CareerMemoryBooster(repo, true, true);
        assertEquals(2, booster.computeBoost(userId, job("Platform Engineer", "Acme", "kubernetes, docker")));
        assertEquals(0, booster.computeBoost(userId, job("Frontend Engineer", "Acme", "react, css")),
                "job doesn't mention the remembered technology at all");
    }

    @Test
    void negativeTechnologyMemoryPenalizesMatchingJob() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        when(repo.findActiveByUserId(any(), any(Instant.class))).thenReturn(List.of(
                techMemory("TECHNOLOGY_NEGATIVE", "React")));
        CareerMemoryBooster booster = new CareerMemoryBooster(repo, true, true);
        assertEquals(-2, booster.computeBoost(userId, job("Frontend Engineer", "Acme", "react, redux")));
    }

    @Test
    void nonTechnologyCategoryMemoryDoesNotAffectTechnologyMatching() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        // Same value, but wrong category — must not be treated as a technology signal.
        when(repo.findActiveByUserId(any(), any(Instant.class))).thenReturn(List.of(
                CareerDecisionMemory.builder().id(UUID.randomUUID()).userId(userId)
                        .decisionType("CAREER_POSITIVE").category("CAREER").value("Kubernetes")
                        .createdAt(Instant.now()).build()));
        CareerMemoryBooster booster = new CareerMemoryBooster(repo, true, true);
        assertEquals(0, booster.computeBoost(userId, job("Platform Engineer", "Acme", "kubernetes")));
    }

    @Test
    void repositoryFailureNeverPropagates() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        when(repo.findActiveByUserId(any(), any(Instant.class))).thenThrow(new RuntimeException("db down"));
        CareerMemoryBooster booster = new CareerMemoryBooster(repo, true, true);
        assertEquals(0, assertDoesNotThrow(() -> booster.computeBoost(userId, job("Engineer", "Amazon", "java"))));
    }

    @Test
    void nullJobIsZeroNotAnException() {
        CareerMemoryBooster booster = new CareerMemoryBooster(mock(CareerDecisionMemoryRepository.class), true, true);
        assertEquals(0, assertDoesNotThrow(() -> booster.computeBoost(userId, null)));
    }

    private CareerDecisionMemory appMemory(String decisionType, String value) {
        return CareerDecisionMemory.builder()
                .id(UUID.randomUUID()).userId(userId)
                .decisionType(decisionType).category("APPLICATION").value(value)
                .createdAt(Instant.now()).build();
    }

    private CareerDecisionMemory techMemory(String decisionType, String value) {
        return CareerDecisionMemory.builder()
                .id(UUID.randomUUID()).userId(userId)
                .decisionType(decisionType).category("TECHNOLOGY").value(value)
                .createdAt(Instant.now()).build();
    }
}
