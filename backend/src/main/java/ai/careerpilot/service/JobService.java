package ai.careerpilot.service;

import ai.careerpilot.domain.Job;
import ai.careerpilot.repo.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class JobService {

    private final JobRepository jobs;
    private final int recommendThreshold;

    public JobService(JobRepository jobs,
                      @Value("${jobs.recommendation.threshold:75}") int recommendThreshold) {
        this.jobs = jobs;
        this.recommendThreshold = recommendThreshold;
    }

    public Page<Job> search(UUID orgId, String q, int page, int size) {
        return jobs.search(orgId, q, PageRequest.of(page, Math.min(size, 100)));
    }

    /**
     * Read the global discovered-job pool (org_id IS NULL) for the Domestic/International tabs,
     * with optional facets (remoteType / sponsorship / relocation) + country search.
     * {@code domestic} = jobs in the given country; otherwise everything outside it (incl. unknown).
     */
    public Page<Job> discovered(String scope, String country, String remoteType,
                                Boolean sponsorship, Boolean relocation, String q,
                                int page, int size) {
        var pageable = PageRequest.of(page, Math.min(size, 100));
        String c = (country == null || country.isBlank()) ? "India" : country.trim();
        String scopeNorm = "domestic".equalsIgnoreCase(scope) ? "domestic" : "international";
        return jobs.findDiscoveredFiltered(scopeNorm, c,
                blankToNull(remoteType), sponsorship, relocation, blankToNull(q), pageable);
    }

    /** Browse "more opportunities": discovered jobs that didn't clear this user's Recommended bar. */
    public Page<Job> browsePool(UUID userId, int page, int size) {
        return jobs.findBrowsePool(userId, recommendThreshold, PageRequest.of(page, Math.min(size, 100)));
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    @Transactional
    public Job create(UUID orgId, Job j) {
        j.setOrgId(orgId);
        return jobs.save(j);
    }

    public Job get(UUID orgId, UUID id) {
        return jobs.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found or access denied"));
    }
}
