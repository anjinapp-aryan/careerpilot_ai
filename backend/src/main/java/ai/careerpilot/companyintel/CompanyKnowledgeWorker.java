package ai.careerpilot.companyintel;

import ai.careerpilot.companyintel.config.CompanyIntelExecutorConfig;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.learning.LearningEventType;
import ai.careerpilot.learning.event.LearningEventRecordedEvent;
import ai.careerpilot.repo.JobAiEnrichmentRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.LearningEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 7.13 — the Learning-Engine → Company-Knowledge bridge: every outcome the learning engine
 * records (application submitted, interview, offer, rejection, …) also enriches the company's
 * knowledge row, timeline and graph. A NEW consumer on the EXISTING Phase 6.1
 * {@link LearningEventRecordedEvent} — no learning code changes, no duplicated learning.
 *
 * <p>Dark by default: gated by BOTH {@code company.learning.enabled} and the knowledge service's
 * own {@code company.knowledge.enabled}. Runs on the dedicated bounded executor; failures are
 * logged and isolated (knowledge enrichment must never break the learning pipeline).
 */
@Component
public class CompanyKnowledgeWorker {

    private static final Logger log = LoggerFactory.getLogger(CompanyKnowledgeWorker.class);

    private final CompanyKnowledgeService knowledge;
    private final LearningEventRepository learningEvents;
    private final JobRepository jobs;
    private final JobAiEnrichmentRepository enrichment;
    private final ThreadPoolTaskExecutor executor;
    private final boolean learningEnabled;

    public CompanyKnowledgeWorker(CompanyKnowledgeService knowledge,
                                  LearningEventRepository learningEvents,
                                  JobRepository jobs,
                                  JobAiEnrichmentRepository enrichment,
                                  @Qualifier(CompanyIntelExecutorConfig.COMPANY_INTEL_EXECUTOR) ThreadPoolTaskExecutor executor,
                                  @Value("${company.learning.enabled:false}") boolean learningEnabled) {
        this.knowledge = knowledge;
        this.learningEvents = learningEvents;
        this.jobs = jobs;
        this.enrichment = enrichment;
        this.executor = executor;
        this.learningEnabled = learningEnabled;
    }

    public boolean isEnabled() {
        return learningEnabled && knowledge.isEnabled();
    }

    @Async(CompanyIntelExecutorConfig.COMPANY_INTEL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLearningEventRecorded(LearningEventRecordedEvent event) {
        if (!isEnabled()) return;
        try {
            executor.execute(() -> ingest(event));
        } catch (Exception e) {
            log.warn("COMPANY_INTEL worker dispatch failed event={}: {}", event.learningEventId(), e.toString());
        }
    }

    void ingest(LearningEventRecordedEvent event) {
        try {
            LearningEvent le = learningEvents.findById(event.learningEventId()).orElse(null);
            String company = le != null ? le.getCompany() : null;
            Job job = event.jobId() != null ? jobs.findById(event.jobId()).orElse(null) : null;
            if ((company == null || company.isBlank()) && job != null) company = job.getCompany();
            if (company == null || company.isBlank()) return; // nothing observed to attach knowledge to

            if (job != null) {
                knowledge.ingestJob(event.userId(), job,
                        enrichment.findByJobId(job.getId()).orElse(null), KnowledgeSource.LEARNING_ENGINE);
            }
            CompanyTimelineEventType type = mapEventType(event.eventType());
            if (type != null) {
                knowledge.recordOutcome(event.userId(), company, type, event.jobId(),
                        "learning event " + event.eventType());
            }
        } catch (Exception e) {
            log.warn("COMPANY_INTEL ingest failed event={}: {}", event.learningEventId(), e.toString());
        }
    }

    /** Learning outcome → timeline milestone; null for signals with no company milestone. */
    static CompanyTimelineEventType mapEventType(LearningEventType type) {
        if (type == null) return null;
        return switch (type) {
            case APPLICATION_SUBMITTED -> CompanyTimelineEventType.APPLICATION_SUBMITTED;
            case APPLICATION_REJECTED, OFFER_REJECTED, RESUME_REJECTED, RECOMMENDATION_REJECTED ->
                    CompanyTimelineEventType.REJECTED;
            case INTERVIEW_SCHEDULED -> CompanyTimelineEventType.INTERVIEW_SCHEDULED;
            case INTERVIEW_COMPLETED -> CompanyTimelineEventType.INTERVIEW_COMPLETED;
            case OFFER_RECEIVED, OFFER_ACCEPTED, APPLICATION_ACCEPTED -> CompanyTimelineEventType.OFFER_RECEIVED;
            case RECOMMENDATION_APPROVED -> CompanyTimelineEventType.RECOMMENDED;
            case RESUME_SELECTED, WORKFLOW_COMPLETED, WORKFLOW_FAILED -> CompanyTimelineEventType.LEARNING_UPDATE;
        };
    }
}
