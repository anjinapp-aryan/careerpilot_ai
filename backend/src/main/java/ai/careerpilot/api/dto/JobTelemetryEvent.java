package ai.careerpilot.api.dto;

/**
 * Lightweight UI interaction event for the Jobs surface (filter usage, apply/save/why-match
 * clicks). Logged server-side under the JOB_TELEMETRY marker — no storage, no PII beyond the
 * authenticated user id the controller already has.
 */
public record JobTelemetryEvent(String event, String jobId, String filter) {}
