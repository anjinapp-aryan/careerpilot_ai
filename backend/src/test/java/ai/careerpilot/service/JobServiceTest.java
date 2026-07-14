package ai.careerpilot.service;

import ai.careerpilot.discovery.relevance.CareerRelevanceEvaluator;
import ai.careerpilot.discovery.relevance.CareerThresholdPolicy;
import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.CandidateSignalResolver;
import ai.careerpilot.jobdiscovery.RoleExclusionFilter;
import ai.careerpilot.jobdiscovery.scope.JobScopeStrategyResolver;
import ai.careerpilot.repo.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Covers {@link JobService#getByIds} — the fix for Saved/Applied not resolving discovered-pool
 * jobs (org_id IS NULL, so {@link JobRepository#findByIdAndOrgId} can never match them).
 */
class JobServiceTest {

    private JobRepository jobs;
    private JobService service;

    @BeforeEach
    void setUp() {
        jobs = mock(JobRepository.class);
        service = new JobService(
                jobs,
                mock(JobScopeStrategyResolver.class),
                mock(RoleExclusionFilter.class),
                mock(CandidateSignalResolver.class),
                mock(CareerRelevanceEvaluator.class),
                mock(CareerThresholdPolicy.class),
                75,
                false,
                false,
                false);
    }

    @Test
    void getByIdsReturnsEmptyListForEmptyInput() {
        List<Job> result = service.getByIds(List.of());

        assertTrue(result.isEmpty());
        verifyNoInteractions(jobs);
    }

    @Test
    void getByIdsDelegatesToRepositoryFindAllById() {
        UUID orgScoped = UUID.randomUUID();
        UUID discovered = UUID.randomUUID();
        Job orgJob = Job.builder().id(orgScoped).title("Org job").company("Acme").description("d").build();
        Job discoveredJob = Job.builder().id(discovered).title("Discovered job").company("Globex").description("d").build();
        when(jobs.findAllById(List.of(orgScoped, discovered))).thenReturn(List.of(orgJob, discoveredJob));

        List<Job> result = service.getByIds(List.of(orgScoped, discovered));

        assertEquals(2, result.size());
        assertTrue(result.contains(orgJob));
        assertTrue(result.contains(discoveredJob));
    }
}
