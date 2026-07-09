package ai.careerpilot.autopilot.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Phase 7.7 — calendar intelligence for detected interviews. No real calendar provider (Google/
 * Outlook OAuth) is wired anywhere in the codebase, so this never claims to have created a real
 * event: it returns {@code NOT_INTEGRATED} with a clear "add it manually" reason, honoring the
 * fail-safe mandate. The seam is ready for a real {@code CalendarProvider} to drop in behind it.
 * Gated by {@code calendar.integration.enabled} (default off).
 */
@Service
public class CalendarIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(CalendarIntelligenceService.class);

    private final boolean enabled;

    public CalendarIntelligenceService(@Value("${calendar.integration.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public enum CalendarStatus { DISABLED, NOT_INTEGRATED, SCHEDULED }

    public record CalendarResult(CalendarStatus status, String reason) {}

    /**
     * Attempt to place an interview + preparation window on the candidate's calendar. Never fabricates
     * a successful scheduling: with no connected provider it returns {@code NOT_INTEGRATED}.
     */
    public CalendarResult scheduleInterviewPrep(UUID userId, UUID jobId, String interviewType, String whenIso) {
        if (!enabled) {
            return new CalendarResult(CalendarStatus.DISABLED, "Calendar integration is disabled.");
        }
        log.info("CALENDAR_INTEL interview user={} job={} type={} when={} (no provider connected)",
                userId, jobId, interviewType, whenIso);
        return new CalendarResult(CalendarStatus.NOT_INTEGRATED,
                "No calendar provider connected — add this " + interviewType + " interview to your calendar manually.");
    }
}
