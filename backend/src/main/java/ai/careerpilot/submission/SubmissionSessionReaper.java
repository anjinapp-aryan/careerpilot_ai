package ai.careerpilot.submission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The trigger that makes {@link ApplicationSubmissionSessionService#reapStranded} reachable. It adds
 * no policy of its own — it calls one existing method, exactly the shape
 * {@code BrowserMaintenanceScheduler} uses for the browser lease pool's own reclaim.
 *
 * <p><b>Why a startup sweep as well as a periodic one.</b> The failure mode being recovered from is
 * precisely a backend restart: the sessions that need reaping were stranded by the previous JVM's
 * death, so the moment they are recoverable is the moment the next JVM finishes booting. Waiting a
 * full interval first would leave the UI showing a spinner for a pipeline that has been dead since
 * before this process existed. The periodic sweep then covers strandings that happen while this JVM
 * is alive (executor rejection, uncaught {@code Error}).
 *
 * <p>Both paths are independently try/caught: a failure in one must not disable the other's
 * recovery, matching the same discipline as the browser maintenance sweep.
 */
@Component
public class SubmissionSessionReaper {

    private static final Logger log = LoggerFactory.getLogger(SubmissionSessionReaper.class);

    private final ApplicationSubmissionSessionService sessionService;
    private final boolean enabled;
    private final Duration staleAfter;

    public SubmissionSessionReaper(
            ApplicationSubmissionSessionService sessionService,
            @Value("${application.submission.reaper.enabled:true}") boolean enabled,
            @Value("${application.submission.reaper.stale-after-minutes:30}") long staleAfterMinutes) {
        this.sessionService = sessionService;
        this.enabled = enabled;
        this.staleAfter = Duration.ofMinutes(staleAfterMinutes);
    }

    /**
     * Defaults to on, unlike most flags in this codebase, because it is gated twice: it does nothing
     * at all unless {@code application.submission.enabled} is also on (checked inside
     * {@code reapStranded}). On a deployment where the submission pipeline is dark this is a no-op;
     * on one where it is live, a reaper defaulting to off would leave the hang unfixed.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /** Recover sessions stranded by the PREVIOUS process, as soon as this one is ready. */
    @EventListener(ApplicationReadyEvent.class)
    public void reapOnStartup() {
        if (!enabled) return;
        try {
            sessionService.reapStranded(staleAfter);
        } catch (Exception e) {
            log.warn("APP_SUBMISSION startup reap failed: {}", e.toString());
        }
    }

    /** Recover sessions stranded while THIS process is running. */
    @Scheduled(fixedDelayString = "${application.submission.reaper.interval-ms:600000}",
               initialDelayString = "${application.submission.reaper.interval-ms:600000}")
    public void reapPeriodically() {
        if (!enabled) return;
        try {
            sessionService.reapStranded(staleAfter);
        } catch (Exception e) {
            log.warn("APP_SUBMISSION periodic reap failed: {}", e.toString());
        }
    }
}
