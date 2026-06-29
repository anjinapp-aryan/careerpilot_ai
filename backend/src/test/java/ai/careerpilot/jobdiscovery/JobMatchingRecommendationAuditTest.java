package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.CandidateProfileVersion;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.RecommendationAudit;
import ai.careerpilot.jobdiscovery.CandidateSignalResolver.CandidateMatchSignals;
import ai.careerpilot.repo.CandidateProfileVersionRepository;
import ai.careerpilot.repo.JobAiEnrichmentRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.RecommendationAuditRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Phase 1.5: {@code recommendation_audit} is purely additive (gated by
 * {@code candidate.recommendation.audit-enabled}) and must never affect the persisted
 * recommendation itself — these tests pin that the flag controls ONLY the audit write.
 */
class JobMatchingRecommendationAuditTest {

    private final JobTaxonomy taxonomy = new JobTaxonomy();
    private final UUID userId = UUID.randomUUID();

    private CandidateMatchSignals signals(String source) {
        return new CandidateMatchSignals(
                List.of("Java", "Spring Boot", "Kubernetes", "AWS"), "Backend Engineer",
                List.of(), 8, null, JobScoring.PreferenceContext.empty(), List.of(), null, source);
    }

    private Job job() {
        return Job.builder().id(UUID.randomUUID())
                .title("Senior Backend Engineer").company("Acme")
                .description("Java Spring Boot Kubernetes AWS").jobFamily("ENGINEERING")
                .build();
    }

    private JobMatchingService matcher(CandidateSignalResolver resolver, JobRepository jobs,
                                       JobRecommendationRepository recommendations,
                                       CandidateProfileVersionRepository profileVersions,
                                       RecommendationAuditRepository audit,
                                       boolean auditEnabled) {
        return new JobMatchingService(resolver, jobs, recommendations, new JobScoring(taxonomy), taxonomy,
                new RoleExclusionFilter(taxonomy), profileVersions, audit,
                mock(JobAiEnrichmentRepository.class),
                false, 0, 0, false, auditEnabled, false);   // strict gate off so the seeded job always qualifies
    }

    @Test
    void auditDisabledWritesNoAuditRowsButStillPersistsRecommendation() {
        CandidateSignalResolver resolver = mock(CandidateSignalResolver.class);
        JobRepository jobs = mock(JobRepository.class);
        JobRecommendationRepository recommendations = mock(JobRecommendationRepository.class);
        CandidateProfileVersionRepository profileVersions = mock(CandidateProfileVersionRepository.class);
        RecommendationAuditRepository audit = mock(RecommendationAuditRepository.class);

        when(resolver.resolve(userId)).thenReturn(Optional.of(signals("PROFILE")));
        when(jobs.findDiscoveredPool(anyInt())).thenReturn(List.of(job()));
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of());

        int written = matcher(resolver, jobs, recommendations, profileVersions, audit, false)
                .refreshForUser(userId);

        assertEquals(1, written);
        verifyNoInteractions(audit);
        verifyNoInteractions(profileVersions);
    }

    @Test
    void auditEnabledWritesOneRowPerPersistedRecommendationWithProfileVersion() {
        CandidateSignalResolver resolver = mock(CandidateSignalResolver.class);
        JobRepository jobs = mock(JobRepository.class);
        JobRecommendationRepository recommendations = mock(JobRecommendationRepository.class);
        CandidateProfileVersionRepository profileVersions = mock(CandidateProfileVersionRepository.class);
        RecommendationAuditRepository audit = mock(RecommendationAuditRepository.class);

        UUID versionId = UUID.randomUUID();
        when(resolver.resolve(userId)).thenReturn(Optional.of(signals("PROFILE")));
        when(jobs.findDiscoveredPool(anyInt())).thenReturn(List.of(job()));
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of());
        when(profileVersions.findByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(CandidateProfileVersion.builder().id(versionId).build()));

        int written = matcher(resolver, jobs, recommendations, profileVersions, audit, true)
                .refreshForUser(userId);

        assertEquals(1, written);
        var captor = org.mockito.ArgumentCaptor.forClass(RecommendationAudit.class);
        verify(audit, times(1)).save(captor.capture());
        RecommendationAudit row = captor.getValue();
        assertEquals(userId, row.getUserId());
        assertEquals("PROFILE", row.getProfileSource());
        assertEquals(versionId, row.getProfileVersion());
        assertTrue(row.getFinalScore() > 0);
    }

    @Test
    void auditEnabledButLegacySourceLeavesProfileVersionNull() {
        CandidateSignalResolver resolver = mock(CandidateSignalResolver.class);
        JobRepository jobs = mock(JobRepository.class);
        JobRecommendationRepository recommendations = mock(JobRecommendationRepository.class);
        CandidateProfileVersionRepository profileVersions = mock(CandidateProfileVersionRepository.class);
        RecommendationAuditRepository audit = mock(RecommendationAuditRepository.class);

        when(resolver.resolve(userId)).thenReturn(Optional.of(signals("WORKFLOW")));
        when(jobs.findDiscoveredPool(anyInt())).thenReturn(List.of(job()));
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of());

        matcher(resolver, jobs, recommendations, profileVersions, audit, true).refreshForUser(userId);

        var captor = org.mockito.ArgumentCaptor.forClass(RecommendationAudit.class);
        verify(audit, times(1)).save(captor.capture());
        assertEquals("WORKFLOW", captor.getValue().getProfileSource());
        assertNull(captor.getValue().getProfileVersion());
        verifyNoInteractions(profileVersions);   // never looked up for a non-PROFILE source
    }
}
