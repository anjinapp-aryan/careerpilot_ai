package ai.careerpilot.jobdiscovery.lake;

import ai.careerpilot.domain.JobDuplicateMapping;
import ai.careerpilot.domain.JobNormalized;
import ai.careerpilot.jobdiscovery.dedup.DuplicateScoring;
import ai.careerpilot.repo.JobDuplicateMappingRepository;
import ai.careerpilot.repo.JobNormalizedRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Deterministic (AI-free) deduplication for the Job Lake (Phase 2A Step 6). Collapses listings that
 * are the same posting across providers using the spec's signals — {@code company + title + country
 * + location + checksum} — into one canonical {@link JobNormalized}, recording the relationship in
 * {@code job_duplicate_mapping}.
 *
 * <p>Reuses {@link DuplicateScoring} (the decoupled, unit-tested company/title text matcher already
 * trusted by the embedding-based {@code JobDuplicateDetectionService}) for confirmation — but, unlike
 * that service, needs no embeddings, so it runs purely over {@code job_normalized}.
 *
 * <p>Flag-gated by {@code JOB_DISCOVERY_DEDUP_ENABLED} (default false): a no-op until flipped on.
 * Idempotent: a job already mapped is skipped. The canonical of a cluster maps to itself, so one
 * query over the mapping table yields every member.
 */
@Service
public class JobLakeDeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(JobLakeDeduplicationService.class);

    private final JobNormalizedRepository normalized;
    private final JobDuplicateMappingRepository mappings;
    private final DuplicateScoring scoring;
    private final boolean enabled;
    private final double titleThreshold;

    public JobLakeDeduplicationService(JobNormalizedRepository normalized,
                                       JobDuplicateMappingRepository mappings,
                                       DuplicateScoring scoring,
                                       @Value("${jobs.discovery.lake.dedup-enabled:false}") boolean enabled,
                                       @Value("${jobs.discovery.lake.dedup-title-threshold:0.6}") double titleThreshold) {
        this.normalized = normalized;
        this.mappings = mappings;
        this.scoring = scoring;
        this.enabled = enabled;
        this.titleThreshold = titleThreshold;
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Resolve and persist the canonical job for {@code job}. Returns the canonical job's id (which is
     * {@code job}'s own id when it's the first/only member). No-op returning {@code job.getId()} when
     * the flag is off or the job is already mapped.
     */
    public UUID deduplicate(JobNormalized job) {
        if (!enabled) return job.getId();
        var existing = mappings.findBySourceJob(job.getId());
        if (existing.isPresent()) {
            return existing.get().getCanonicalJob();
        }

        UUID canonical = findCanonical(job);
        save(job.getId(), canonical);
        return canonical;
    }

    /** Find the canonical job id for {@code job}, or {@code job}'s own id if it starts a new cluster. */
    private UUID findCanonical(JobNormalized job) {
        for (JobNormalized cand : normalized.findDedupCandidates(job.getCountry(), job.getCity())) {
            if (cand.getId().equals(job.getId())) continue;
            if (!isSamePosting(job, cand)) continue;
            // Map onto the candidate's existing canonical (cluster join), or the candidate itself.
            return mappings.findBySourceJob(cand.getId()).map(JobDuplicateMapping::getCanonicalJob).orElse(cand.getId());
        }
        return job.getId(); // first member → its own canonical
    }

    private boolean isSamePosting(JobNormalized a, JobNormalized b) {
        // Identical normalized identity → certain duplicate (cheap short-circuit).
        if (a.getChecksum() != null && a.getChecksum().equals(b.getChecksum())) return true;
        // Otherwise require same company AND a strong title overlap (country/city already matched by the bucket query).
        return scoring.companyMatches(a.getCompany(), b.getCompany())
                && scoring.titleJaccard(a.getTitle(), b.getTitle()) >= titleThreshold;
    }

    private void save(UUID source, UUID canonical) {
        if (mappings.existsBySourceJob(source)) return;
        mappings.save(JobDuplicateMapping.builder()
                .sourceJob(source)
                .canonicalJob(canonical)
                .build());
        if (!source.equals(canonical)) {
            log.debug("lake dedup: {} → canonical {}", source, canonical);
        }
    }

    /** How many provider listings collapsed into this canonical (cluster size, ≥1). */
    public int sourceCount(UUID canonicalJobId) {
        return (int) Math.max(1, mappings.countByCanonicalJob(canonicalJobId));
    }
}
