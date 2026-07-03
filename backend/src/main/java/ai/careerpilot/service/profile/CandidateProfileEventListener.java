package ai.careerpilot.service.profile;

import ai.careerpilot.service.profile.event.PreferencesUpdatedEvent;
import ai.careerpilot.service.profile.event.ResumeChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Decoupled bridge from the resume/preferences flows into profile generation.
 *
 * <ul>
 *   <li><b>After commit</b> ({@link TransactionPhase#AFTER_COMMIT}) so it reads the committed
 *       resume/preferences, never a dirty in-flight row.</li>
 *   <li><b>Async</b> ({@code @EnableAsync} is on the application class) so an LLM extraction
 *       never adds latency to resume upload or a preferences save.</li>
 *   <li><b>Feature-flagged</b> by {@code CANDIDATE_PROFILE_ENABLED} (default false) — a no-op
 *       when disabled, so the feature ships dark.</li>
 *   <li><b>Failure-isolated</b> — every handler swallows its exceptions so a profile error can
 *       never affect the originating Resume Upload / Optimizer / Preferences flow.</li>
 * </ul>
 */
@Component
public class CandidateProfileEventListener {

    private static final Logger log = LoggerFactory.getLogger(CandidateProfileEventListener.class);

    private final CandidateProfileService service;
    private final boolean enabled;

    public CandidateProfileEventListener(CandidateProfileService service,
                                         @Value("${candidate.profile.enabled:false}") boolean enabled) {
        this.service = service;
        this.enabled = enabled;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onResumeChanged(ResumeChangedEvent event) {
        if (!enabled) return;
        try {
            service.onResumeChanged(event.userId(), event.resumeId(), event.reason());
        } catch (Exception e) {
            log.warn("Candidate profile generation failed for resume event (user={}): {}",
                    event.userId(), e.toString());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPreferencesUpdated(PreferencesUpdatedEvent event) {
        if (!enabled) return;
        try {
            service.onPreferencesChanged(event.userId());
        } catch (Exception e) {
            log.warn("Candidate profile re-merge failed for preferences event (user={}): {}",
                    event.userId(), e.toString());
        }
    }
}
