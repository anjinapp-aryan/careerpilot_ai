package ai.careerpilot.jobdiscovery.dedup;

import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobDuplicate;
import ai.careerpilot.repo.JobDuplicateRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.JobRepository.DuplicateCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Detects cross-source duplicate job postings (Phase 2 Increment C): the same job posted to
 * multiple boards (e.g. RemoteOK and Arbeitnow) under different {@code external_id}s. Decoupled
 * from ingest/embedding/enrichment so a detection failure can never roll back a discovery run.
 *
 * <p>Two independent signals must agree before something is classified a duplicate:
 * <ol>
 *   <li><b>Embedding cosine similarity</b> (Increment A) — the candidate-generation signal,
 *       via pgvector nearest-neighbor search restricted to a different source.</li>
 *   <li><b>Title/company text confirmation</b> ({@link DuplicateScoring}) — guards against two
 *       different roles at the same company being merged just because their descriptions read
 *       similarly.</li>
 * </ol>
 *
 * <p><b>Idempotent + capped</b>: only touches jobs with no {@code job_duplicates} row yet, up to
 * {@code jobs.dedup.max-per-run}. <b>Flag-gated</b>: a no-op when {@code jobs.dedup.enabled} is
 * off. <b>Produced-but-not-consumed in v1</b>: nothing filters listings on this yet — see
 * {@code AdminStatsService} for how it's surfaced.
 *
 * <p>Known v1 simplification: if a job matches a neighbor that itself already belongs to a
 * cluster, it joins that cluster (correct). If two already-separate clusters should actually be
 * the same group (a 3-way transitive match), this pass does not merge them — rare at this scale,
 * and never produces a wrong answer, only a slightly fragmented one.
 */
@Service
public class JobDuplicateDetectionService {

    private static final Logger log = LoggerFactory.getLogger(JobDuplicateDetectionService.class);

    private final JobRepository jobs;
    private final JobDuplicateRepository duplicates;
    private final DuplicateScoring scoring;
    private final boolean enabled;
    private final int maxPerRun;
    private final double embeddingThreshold;
    private final double titleJaccardThreshold;

    public JobDuplicateDetectionService(JobRepository jobs,
                                        JobDuplicateRepository duplicates,
                                        DuplicateScoring scoring,
                                        @Value("${jobs.dedup.enabled:false}") boolean enabled,
                                        @Value("${jobs.dedup.max-per-run:200}") int maxPerRun,
                                        @Value("${jobs.dedup.embedding-threshold:0.92}") double embeddingThreshold,
                                        @Value("${jobs.dedup.title-jaccard-threshold:0.55}") double titleJaccardThreshold) {
        this.jobs = jobs;
        this.duplicates = duplicates;
        this.scoring = scoring;
        this.enabled = enabled;
        this.maxPerRun = maxPerRun;
        this.embeddingThreshold = embeddingThreshold;
        this.titleJaccardThreshold = titleJaccardThreshold;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Detect duplicates among up to the configured cap of newest unchecked jobs. Returns count checked. */
    public int detectDuplicates() {
        return detectDuplicates(maxPerRun);
    }

    @Transactional
    public int detectDuplicates(int limit) {
        if (!enabled) return 0;
        List<Job> batch = jobs.findDiscoveredMissingDuplicateCheck(Math.max(0, limit));
        int checked = 0;
        for (Job job : batch) {
            if (detectOneIsolated(job)) checked++;
        }
        if (!batch.isEmpty()) {
            log.info("JOB_DEDUP candidates={} checked={} cap={}", batch.size(), checked, limit);
        }
        return checked;
    }

    private boolean detectOneIsolated(Job job) {
        try {
            detectOne(job);
            return true;
        } catch (Exception e) {
            log.warn("Duplicate detection failed (jobId={}): {}", job.getId(), e.toString());
            return false;
        }
    }

    private void detectOne(Job job) {
        String vec = jobs.findEmbeddingVectorText(job.getId()).orElse(null);
        if (vec == null) {
            // No embedding yet — leave unchecked so a later pass (after embedding) can classify it.
            return;
        }
        List<DuplicateCandidate> neighbors = jobs.findNearestCrossSource(vec, job.getId(), job.getSource(), 3);

        for (DuplicateCandidate neighbor : neighbors) {
            double embeddingSim = 1.0 - neighbor.getDistance();
            if (embeddingSim < embeddingThreshold) break; // neighbors are distance-sorted; none further will qualify

            boolean companyMatch = scoring.companyMatches(job.getCompany(), neighbor.getCompany());
            double titleSim = scoring.titleJaccard(job.getTitle(), neighbor.getTitle());
            if (!companyMatch && titleSim < titleJaccardThreshold) continue; // candidate rejected, try next

            String signals = String.format(Locale.ROOT, "embeddingSim=%.3f,titleJaccard=%.3f,companyMatch=%s",
                    embeddingSim, titleSim, companyMatch);
            joinOrCreateCluster(job, neighbor, embeddingSim, signals);
            return; // first qualifying neighbor wins; no need to also check the others
        }
        // No qualifying neighbor — mark as its own (single-member) canonical so it doesn't get
        // re-checked every run; harmless if a future job turns out to duplicate it later (that
        // job's pass will find this one as the existing-cluster neighbor and join it).
        recordSelfCanonical(job);
    }

    private void joinOrCreateCluster(Job job, DuplicateCandidate neighbor, double similarity, String signals) {
        JobDuplicate neighborRow = duplicates.findByJobId(neighbor.getId()).orElse(null);
        UUID groupId;
        UUID canonicalId;
        if (neighborRow != null) {
            groupId = neighborRow.getDuplicateGroupId();
            canonicalId = neighborRow.getCanonicalJobId();
        } else {
            groupId = UUID.randomUUID();
            canonicalId = job.getCreatedAt().isBefore(neighbor.getCreatedAt()) ? job.getId() : neighbor.getId();
            ensureRow(neighbor.getId(), canonicalId, groupId, neighbor.getId().equals(canonicalId) ? BigDecimal.ONE : BigDecimal.valueOf(similarity), signals);
        }
        ensureRow(canonicalId, canonicalId, groupId, BigDecimal.ONE, "canonical");
        ensureRow(job.getId(), canonicalId, groupId, BigDecimal.valueOf(similarity), signals);
    }

    private void recordSelfCanonical(Job job) {
        ensureRow(job.getId(), job.getId(), UUID.randomUUID(), BigDecimal.ONE, "no-match");
    }

    private void ensureRow(UUID jobId, UUID canonicalId, UUID groupId, BigDecimal score, String signals) {
        if (duplicates.findByJobId(jobId).isPresent()) return;
        duplicates.save(JobDuplicate.builder()
                .jobId(jobId).canonicalJobId(canonicalId).duplicateGroupId(groupId)
                .similarityScore(score).matchSignals(signals).build());
    }
}
