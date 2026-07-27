package ai.careerpilot.career.monitor;

import java.util.UUID;

/** Phase 11.5 — the top-level facade: run every detector, dedupe against history, prioritize. */
public interface CareerMonitor {

    CareerInsights monitor(UUID userId);
}
