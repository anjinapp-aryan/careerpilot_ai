package ai.careerpilot.career.monitor;

import java.util.List;
import java.util.UUID;

/**
 * Phase 11.5 — detects the six non-opportunity alert categories: resume outdated, missing
 * certification, salary below market, promotion readiness, interview reminders, learning
 * suggestions. See {@link CareerOpportunityDetector} for the seventh (job matches).
 */
public interface CareerEventEngine {

    List<CareerAlert> detectEvents(UUID userId);
}
