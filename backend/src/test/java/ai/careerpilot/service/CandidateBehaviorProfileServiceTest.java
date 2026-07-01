package ai.careerpilot.service;

import ai.careerpilot.domain.CandidateBehaviorProfile;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobAiEnrichment;
import ai.careerpilot.domain.RecommendationFeedback;
import ai.careerpilot.jobdiscovery.JobTaxonomy;
import ai.careerpilot.repo.CandidateBehaviorProfileRepository;
import ai.careerpilot.repo.JobAiEnrichmentRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.RecommendationFeedbackRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Phase 2C-5 — the behavior profile splits accepted vs declined jobs into preferred_* / rejected_*
 * buckets by frequency. These tests pin the positive/negative action split and that the deterministic
 * signals (country, work-mode, enrichment domain) land in the right bucket.
 */
class CandidateBehaviorProfileServiceTest {

    private final JobTaxonomy taxonomy = new JobTaxonomy();
    private final UUID userId = UUID.randomUUID();
    private final UUID jobA = UUID.randomUUID();  // approved
    private final UUID jobB = UUID.randomUUID();  // rejected

    private CandidateBehaviorProfileService service(RecommendationFeedbackRepository fb,
                                                    CandidateBehaviorProfileRepository profiles,
                                                    JobRepository jobs,
                                                    JobAiEnrichmentRepository enrich,
                                                    boolean enabled) {
        when(profiles.save(any(CandidateBehaviorProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        return new CandidateBehaviorProfileService(fb, profiles, jobs, taxonomy, enrich, enabled);
    }

    @Test
    void aggregatesAcceptedVsDeclinedIntoTheRightBuckets() {
        RecommendationFeedbackRepository fb = mock(RecommendationFeedbackRepository.class);
        CandidateBehaviorProfileRepository profiles = mock(CandidateBehaviorProfileRepository.class);
        JobRepository jobs = mock(JobRepository.class);
        JobAiEnrichmentRepository enrich = mock(JobAiEnrichmentRepository.class);

        when(fb.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(
                RecommendationFeedback.builder().userId(userId).jobId(jobA).action("APPROVE").build(),
                RecommendationFeedback.builder().userId(userId).jobId(jobB).action("REJECT").build()));
        when(jobs.findAllById(anyList())).thenReturn(List.of(
                Job.builder().id(jobA).title("Java Architect").country("Germany").remoteType("REMOTE").build(),
                Job.builder().id(jobB).title("Sales Manager").country("France").remoteType("ONSITE").build()));
        when(enrich.findByJobIdIn(anyList())).thenReturn(List.of(
                JobAiEnrichment.builder().jobId(jobA).domainsJson("[\"Fintech\"]").build()));
        when(profiles.findById(userId)).thenReturn(Optional.empty());

        CandidateBehaviorProfile p = service(fb, profiles, jobs, enrich, true).rebuild(userId);

        assertEquals("Germany", p.getPreferredCountries());
        assertEquals("France", p.getRejectedCountries());
        assertEquals("REMOTE", p.getPreferredWorkModes());
        assertEquals("ONSITE", p.getRejectedWorkModes());
        assertEquals("Fintech", p.getPreferredDomains());
        assertNull(p.getRejectedDomains());          // jobB had no enrichment
        assertNotNull(p.getPreferredRoles());        // architect maps to a role family
        assertNotNull(p.getRejectedRoles());         // sales/manager maps to a role family
    }

    @Test
    void emptyFeedbackProducesAnAllNullProfile() {
        RecommendationFeedbackRepository fb = mock(RecommendationFeedbackRepository.class);
        CandidateBehaviorProfileRepository profiles = mock(CandidateBehaviorProfileRepository.class);
        JobRepository jobs = mock(JobRepository.class);
        JobAiEnrichmentRepository enrich = mock(JobAiEnrichmentRepository.class);
        when(fb.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(profiles.findById(userId)).thenReturn(Optional.empty());

        CandidateBehaviorProfile p = service(fb, profiles, jobs, enrich, true).rebuild(userId);

        assertEquals(userId, p.getUserId());
        assertNull(p.getPreferredCountries());
        assertNull(p.getRejectedRoles());
        verifyNoInteractions(jobs, enrich);
    }

    @Test
    void applyLaterCountsAsPreferred() {
        RecommendationFeedbackRepository fb = mock(RecommendationFeedbackRepository.class);
        CandidateBehaviorProfileRepository profiles = mock(CandidateBehaviorProfileRepository.class);
        JobRepository jobs = mock(JobRepository.class);
        JobAiEnrichmentRepository enrich = mock(JobAiEnrichmentRepository.class);
        when(fb.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(
                RecommendationFeedback.builder().userId(userId).jobId(jobA).action("APPLY_LATER").build()));
        when(jobs.findAllById(anyList())).thenReturn(List.of(
                Job.builder().id(jobA).title("Java Architect").country("India").remoteType("HYBRID").build()));
        when(enrich.findByJobIdIn(anyList())).thenReturn(List.of());
        when(profiles.findById(userId)).thenReturn(Optional.empty());

        CandidateBehaviorProfile p = service(fb, profiles, jobs, enrich, true).rebuild(userId);

        assertEquals("India", p.getPreferredCountries());
        assertEquals("HYBRID", p.getPreferredWorkModes());
        assertNull(p.getRejectedCountries());
    }

    @Test
    void disabledFlagReflectsConstruction() {
        var svc = new CandidateBehaviorProfileService(mock(RecommendationFeedbackRepository.class),
                mock(CandidateBehaviorProfileRepository.class), mock(JobRepository.class),
                taxonomy, mock(JobAiEnrichmentRepository.class), false);
        assertFalse(svc.isEnabled());
    }
}
