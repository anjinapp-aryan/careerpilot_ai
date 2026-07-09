package ai.careerpilot.autopilot.apply;

import ai.careerpilot.autopilot.provider.ApplicationProvider;
import ai.careerpilot.autopilot.provider.ApplicationProviderRegistry;
import ai.careerpilot.autopilot.provider.ApplicationSubmissionRequest;
import ai.careerpilot.autopilot.provider.SubmissionResult;
import ai.careerpilot.autopilot.provider.SubmissionStatus;
import ai.careerpilot.domain.ApplicationSubmission;
import ai.careerpilot.domain.Job;
import ai.careerpilot.execution.event.ApplicationSubmittedEvent;
import ai.careerpilot.repo.ApplicationSubmissionRepository;
import ai.careerpilot.repo.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7.4 — attempts to submit an application through the {@link ApplicationProviderRegistry},
 * behind which the (non-existent today) browser/HTTP layer lives. No provider-specific or browser
 * logic lives here.
 *
 * <p>Fails safe by construction: no URL/provider, an unconfigured provider, or a submit exception
 * all record {@code HUMAN_REVIEW}/{@code FAILED} — the agent never persists a fabricated
 * {@code SUBMITTED}. Only a genuine {@code SUBMITTED} publishes the existing
 * {@link ApplicationSubmittedEvent} (which the Phase 6 learning engine already consumes), so the
 * learning loop can never be poisoned by a fake application. Flag-gated by
 * {@code application.auto.enabled} (default off); disabled is a no-op returning empty.
 */
@Service
public class AutoApplyEngine {

    private static final Logger log = LoggerFactory.getLogger(AutoApplyEngine.class);

    private final ApplicationProviderRegistry registry;
    private final JobRepository jobs;
    private final ApplicationSubmissionRepository submissions;
    private final ApplicationEventPublisher publisher;
    private final boolean enabled;

    public AutoApplyEngine(ApplicationProviderRegistry registry, JobRepository jobs,
                           ApplicationSubmissionRepository submissions, ApplicationEventPublisher publisher,
                           @Value("${application.auto.enabled:false}") boolean enabled) {
        this.registry = registry;
        this.jobs = jobs;
        this.submissions = submissions;
        this.publisher = publisher;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Attempt to apply to (user, job) with the given tailored resume. Empty when disabled. Always
     * persists exactly one {@link ApplicationSubmission} audit row describing the real outcome.
     */
    @Transactional
    public Optional<ApplicationSubmission> apply(UUID userId, UUID jobId, UUID resumeTailoringId) {
        if (!enabled) return Optional.empty();

        Job job = jobs.findById(jobId).orElse(null);
        String url = job == null ? null
                : (job.getSourceUrl() != null ? job.getSourceUrl() : job.getExternalUrl());

        ApplicationProvider provider = registry.resolve(url).orElse(null);
        SubmissionResult result = resolveResult(provider, userId, jobId, url, resumeTailoringId);

        ApplicationSubmission saved = submissions.save(ApplicationSubmission.builder()
                .userId(userId).jobId(jobId)
                .provider(result.provider())
                .status(result.status().name())
                .externalReference(result.externalReference())
                .reason(result.reason())
                .resumeTailoringId(resumeTailoringId)
                .build());

        // Only a genuine submission feeds the tracking + learning loop.
        if (result.status() == SubmissionStatus.SUBMITTED) {
            publisher.publishEvent(new ApplicationSubmittedEvent(userId, jobId, null, saved.getId(), result.provider()));
        }
        log.info("AUTO_APPLY user={} job={} provider={} status={}",
                userId, jobId, result.provider(), result.status());
        return Optional.of(saved);
    }

    private SubmissionResult resolveResult(ApplicationProvider provider, UUID userId, UUID jobId,
                                           String url, UUID resumeTailoringId) {
        if (provider == null) {
            return SubmissionResult.humanReview("unknown",
                    "No application URL or recognized provider — manual application required.");
        }
        if (!provider.autoSubmitConfigured()) {
            return SubmissionResult.humanReview(provider.name(),
                    provider.name() + " has no automated-submission integration — routed to human review.");
        }
        try {
            return provider.submit(new ApplicationSubmissionRequest(userId, jobId, url, resumeTailoringId));
        } catch (Exception e) {
            log.warn("AUTO_APPLY submit error user={} job={} provider={}: {}", userId, jobId, provider.name(), e.toString());
            return new SubmissionResult(SubmissionStatus.FAILED, provider.name(), null, e.toString());
        }
    }

    public Optional<ApplicationSubmission> latest(UUID userId, UUID jobId) {
        return submissions.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId);
    }
}
