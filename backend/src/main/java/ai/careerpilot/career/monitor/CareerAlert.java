package ai.careerpilot.career.monitor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 11.5 — one proactively-detected insight. {@code evidence} carries the raw fact(s) the
 * alert was derived from (e.g. the matching job's id/score, the resume's age in days) so a
 * future consumer can explain *why* without re-querying — every detector in this package
 * populates it from data that already existed before this phase, never a fabricated value.
 */
public record CareerAlert(UUID id, UUID userId, CareerAlertType type, CareerAlertSeverity severity,
                           String message, Instant detectedAt, Map<String, Object> evidence) {

    public static CareerAlert of(UUID userId, CareerAlertType type, CareerAlertSeverity severity,
                                  String message, Map<String, Object> evidence) {
        return new CareerAlert(UUID.randomUUID(), userId, type, severity, message, Instant.now(), evidence);
    }
}
