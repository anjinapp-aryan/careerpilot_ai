package ai.careerpilot.execution.event;

import java.util.UUID;

/**
 * Phase 2E.3 — published by {@code ATSConnectorWorker} (or the browser worker) ONLY after an
 * application is actually submitted to an external ATS. With the browser layer a throwing stub and
 * all connectors unconfigured, nothing in the 2E build emits this event. Consumers:
 * {@code TrackingWorker} (2E.7) and {@code AnalyticsWorker} (2E.8).
 */
public record ApplicationSubmittedEvent(UUID userId, UUID jobId, UUID executionId,
                                        UUID applicationId, String provider) {
}
