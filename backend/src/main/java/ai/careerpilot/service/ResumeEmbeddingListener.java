package ai.careerpilot.service;

import ai.careerpilot.ai.embedding.EmbeddingService;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.service.profile.event.ResumeChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Generates a resume embedding when a resume changes (upload/optimize), reusing the existing
 * {@link ResumeChangedEvent}. Decoupled exactly like {@code CandidateProfileEventListener}:
 *
 * <ul>
 *   <li><b>After commit + async</b> so embedding never adds latency to resume upload.</li>
 *   <li><b>Flag-gated</b> via {@code EmbeddingService.isEnabled()} ({@code ai.embeddings.enabled}).</li>
 *   <li><b>Failure-isolated</b> — swallows exceptions so an embedding error can never affect the
 *       originating upload/optimize flow.</li>
 * </ul>
 */
@Component
public class ResumeEmbeddingListener {

    private static final Logger log = LoggerFactory.getLogger(ResumeEmbeddingListener.class);

    private final ResumeRepository resumes;
    private final EmbeddingService embeddings;

    public ResumeEmbeddingListener(ResumeRepository resumes, EmbeddingService embeddings) {
        this.resumes = resumes;
        this.embeddings = embeddings;
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onResumeChanged(ResumeChangedEvent event) {
        if (!embeddings.isEnabled()) return;
        try {
            Resume resume = resumes.findById(event.resumeId()).orElse(null);
            if (resume == null) return;
            embeddings.embed(resume.getParsedText()).ifPresent(vec ->
                    resumes.updateEmbedding(resume.getId(), EmbeddingService.toVectorLiteral(vec)));
        } catch (Exception e) {
            log.warn("Resume embedding failed (resumeId={}): {}", event.resumeId(), e.toString());
        }
    }
}
