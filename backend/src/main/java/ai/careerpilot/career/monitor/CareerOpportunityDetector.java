package ai.careerpilot.career.monitor;

import java.util.List;
import java.util.UUID;

/**
 * Phase 11.5 — detects new market opportunities (job matches) for a user. Separate from {@link
 * CareerEventEngine} (which covers the other six alert categories) since "new opportunity
 * appeared" is a distinct kind of signal from "something about your existing profile needs
 * attention."
 */
public interface CareerOpportunityDetector {

    List<CareerAlert> detectOpportunities(UUID userId);
}
