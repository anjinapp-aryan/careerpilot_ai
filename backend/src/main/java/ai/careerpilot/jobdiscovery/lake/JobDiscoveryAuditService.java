package ai.careerpilot.jobdiscovery.lake;

import ai.careerpilot.domain.JobDiscoveryAudit;
import ai.careerpilot.domain.JobDiscoveryFailure;
import ai.careerpilot.repo.JobDiscoveryAuditRepository;
import ai.careerpilot.repo.JobDiscoveryFailureRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Run-level observability for the lake discovery pipeline (Phase 2A Step 10). Writes one
 * {@code job_discovery_audit} row per provider per run and an isolated {@code job_discovery_failure}
 * row whenever a provider blows up — so a failed provider is recorded and the run continues.
 */
@Service
public class JobDiscoveryAuditService {

    private final JobDiscoveryAuditRepository audits;
    private final JobDiscoveryFailureRepository failures;

    public JobDiscoveryAuditService(JobDiscoveryAuditRepository audits,
                                    JobDiscoveryFailureRepository failures) {
        this.audits = audits;
        this.failures = failures;
    }

    /** Open an audit row for a provider at the start of its fetch. */
    public JobDiscoveryAudit start(UUID runId, String provider) {
        return audits.save(JobDiscoveryAudit.builder()
                .runId(runId).provider(provider).build());
    }

    /** Close an audit row with the run's tallies. */
    public void complete(JobDiscoveryAudit audit, int jobsFound, int jobsInserted, int duplicates, int failures) {
        audit.setJobsFound(jobsFound);
        audit.setJobsInserted(jobsInserted);
        audit.setDuplicates(duplicates);
        audit.setFailures(failures);
        audit.setCompletedAt(Instant.now());
        audits.save(audit);
    }

    /** Record an isolated provider failure (the run continues with the next provider). */
    public void recordFailure(UUID runId, String provider, Throwable e) {
        failures.save(JobDiscoveryFailure.builder()
                .runId(runId).provider(provider)
                .error(truncate(String.valueOf(e), 4000))
                .build());
    }

    public List<JobDiscoveryAudit> recentAudits() {
        return audits.findTop50ByOrderByStartedAtDesc();
    }

    public List<JobDiscoveryAudit> auditsForRun(UUID runId) {
        return audits.findByRunIdOrderByStartedAtDesc(runId);
    }

    private static String truncate(String s, int max) {
        return (s == null || s.length() <= max) ? s : s.substring(0, max);
    }
}
