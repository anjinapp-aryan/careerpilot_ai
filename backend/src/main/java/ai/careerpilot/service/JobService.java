package ai.careerpilot.service;

import ai.careerpilot.domain.Job;
import ai.careerpilot.repo.JobRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class JobService {

    private final JobRepository jobs;

    public JobService(JobRepository jobs) { this.jobs = jobs; }

    public Page<Job> search(UUID orgId, String q, int page, int size) {
        return jobs.search(orgId, q, PageRequest.of(page, Math.min(size, 100)));
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
