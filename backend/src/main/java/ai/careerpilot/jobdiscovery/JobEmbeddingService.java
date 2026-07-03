package ai.careerpilot.jobdiscovery;

import ai.careerpilot.ai.embedding.EmbeddingService;
import ai.careerpilot.domain.Job;
import ai.careerpilot.repo.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Generates and queries embeddings for the global discovered-job pool. Decoupled from ingest so the
 * daily fetch stays fast and an embedding failure can never roll back a discovery run.
 *
 * <ul>
 *   <li><b>Idempotent + capped</b>: {@link #embedMissingJobs()} only touches rows with no embedding
 *       yet, up to {@code ai.embeddings.max-per-run}, so re-running is safe and cost is bounded.</li>
 *   <li><b>Flag-gated</b>: a no-op (returns 0 / empty) when {@code ai.embeddings.enabled} is off.</li>
 *   <li><b>Failure-isolated</b>: a per-row embed failure is logged and skipped; the pass continues.</li>
 * </ul>
 */
@Service
public class JobEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(JobEmbeddingService.class);

    private static final int MAX_SEMANTIC_RESULTS = 50;

    private final JobRepository jobs;
    private final EmbeddingService embeddings;
    private final int maxPerRun;

    public JobEmbeddingService(JobRepository jobs,
                               EmbeddingService embeddings,
                               @Value("${ai.embeddings.max-per-run:200}") int maxPerRun) {
        this.jobs = jobs;
        this.embeddings = embeddings;
        this.maxPerRun = maxPerRun;
    }

    /** Embed up to the configured cap of newest jobs missing an embedding. Returns the count written. */
    public int embedMissingJobs() {
        return embedMissingJobs(maxPerRun);
    }

    @Transactional
    public int embedMissingJobs(int limit) {
        if (!embeddings.isEnabled()) return 0;
        List<Job> batch = jobs.findDiscoveredMissingEmbedding(Math.max(0, limit));
        int written = 0;
        for (Job job : batch) {
            var vec = embeddings.embed(embeddingText(job));
            if (vec.isEmpty()) continue;   // disabled mid-run, blank text, or provider failure → skip
            jobs.updateEmbedding(job.getId(), EmbeddingService.toVectorLiteral(vec.get()));
            written++;
        }
        if (written > 0 || !batch.isEmpty()) {
            log.info("JOB_EMBED candidates={} embedded={} cap={}", batch.size(), written, limit);
        }
        return written;
    }

    /** Cosine nearest-neighbor semantic search over the embedded discovered pool. */
    public List<Job> semanticSearch(String query, int k) {
        if (!embeddings.isEnabled() || query == null || query.isBlank()) return List.of();
        var vec = embeddings.embed(query);
        if (vec.isEmpty()) return List.of();
        int limit = Math.max(1, Math.min(k, MAX_SEMANTIC_RESULTS));
        return jobs.findNearestDiscovered(EmbeddingService.toVectorLiteral(vec.get()), limit);
    }

    /** Compact text representation of a job for embedding: title, company, skills, then description. */
    private static String embeddingText(Job job) {
        StringBuilder sb = new StringBuilder();
        append(sb, job.getTitle());
        append(sb, job.getCompany());
        append(sb, job.getSkills());
        append(sb, job.getDescription());
        return sb.toString().trim();
    }

    private static void append(StringBuilder sb, String part) {
        if (part != null && !part.isBlank()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(part.trim());
        }
    }
}
