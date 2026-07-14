package ai.careerpilot.applications;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Application Command Center — deterministic status → suggested-action mapping. Pure lookup
 * table, no LLM. Keyed by {@code ApplicationLifecycle.currentStatus} (Phase 3A's 16 statuses)
 * when workflow tracking is enabled and a lifecycle row exists; falls back to the core
 * {@code applications.status} 6-value enum (SAVED/APPLIED/INTERVIEWING/OFFER/REJECTED/WITHDRAWN)
 * otherwise, so the feature degrades gracefully with {@code workflow.tracking.enabled=false}.
 */
@Service
public class ApplicationNextActionService {

    public record NextAction(String action, Instant suggestedAt) {}

    private record Template(String action, Long dueInDays) {}

    // Phase 3A lifecycle statuses (see ai.careerpilot.domain.ApplicationLifecycle).
    private static final Map<String, Template> LIFECYCLE = Map.ofEntries(
            Map.entry("DRAFT", new Template("Submit this application", 3L)),
            Map.entry("SUBMITTED", new Template("Wait for a response; follow up if silent past 14 days", 14L)),
            Map.entry("VIEWED", new Template("Employer viewed your application — prepare for a possible screen", 7L)),
            Map.entry("UNDER_REVIEW", new Template("Application is under review — sit tight", 7L)),
            Map.entry("ASSESSMENT", new Template("Complete the assessment", 3L)),
            Map.entry("TECHNICAL_INTERVIEW", new Template("Prepare STAR stories for your technical interview", 2L)),
            Map.entry("SYSTEM_DESIGN", new Template("Review system design fundamentals for this round", 2L)),
            Map.entry("MANAGER_INTERVIEW", new Template("Prepare leadership/team-fit stories for your manager interview", 2L)),
            Map.entry("HR_INTERVIEW", new Template("Prepare for HR/logistics questions (comp, start date, culture)", 2L)),
            Map.entry("FINAL_ROUND", new Template("Prepare to discuss offer expectations for the final round", 2L)),
            Map.entry("OFFER_RECEIVED", new Template("Review the offer terms and prepare any negotiation points", 3L)),
            Map.entry("NEGOTIATION", new Template("Finalize your counter-offer", 2L)),
            Map.entry("ACCEPTED", new Template("Offer accepted — no further action needed", null)),
            Map.entry("REJECTED", new Template("Reflect on feedback and apply learnings to your next application", null)),
            Map.entry("WITHDRAWN", new Template("No action needed", null)),
            Map.entry("EXPIRED", new Template("Listing expired — no action needed", null))
    );

    // Core applications.status fallback (pre-lifecycle-tracking).
    private static final Map<String, Template> CORE = Map.of(
            "SAVED", new Template("Apply to this role before it goes cold", 5L),
            "APPLIED", new Template("Wait for a response; follow up if silent past 14 days", 14L),
            "INTERVIEWING", new Template("Prepare STAR stories and research the interviewers", 3L),
            "OFFER", new Template("Review the offer terms and prepare any negotiation points", 3L),
            "REJECTED", new Template("Reflect on feedback and apply learnings to your next application", null),
            "WITHDRAWN", new Template("No action needed", null)
    );

    /**
     * @param lifecycleStatus the Phase 3A lifecycle status, or {@code null} when tracking is
     *                        disabled / no lifecycle row exists yet
     * @param coreStatus      the core {@code applications.status} value (always present)
     */
    public NextAction suggest(String lifecycleStatus, String coreStatus) {
        Template t = lifecycleStatus != null ? LIFECYCLE.get(lifecycleStatus) : null;
        if (t == null) t = CORE.get(coreStatus);
        if (t == null) return new NextAction("Review this application's status", Instant.now().plus(Duration.ofDays(3)));
        Instant due = t.dueInDays() == null ? null : Instant.now().plus(Duration.ofDays(t.dueInDays()));
        return new NextAction(t.action(), due);
    }
}
