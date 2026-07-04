package ai.careerpilot.workflow.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.0 — the common contract every Phase 3A workflow event carries. Implemented (not extended:
 * Java records may only implement interfaces) by every new 3A event record so a correlation id can be
 * threaded through the whole Applied → Offer → Accepted/Rejected workflow and every hop can be audited
 * and dead-lettered against the same {@code correlationId}.
 *
 * <p>Existing 2A–2E events deliberately do NOT implement this — Phase 3A is a new bounded context and
 * touching prior-phase events would risk regression. Correlation is propagated across the new 3A
 * workflows only.
 *
 * <p>{@code jobId} / {@code applicationId} may be null for events that do not carry one yet.
 */
public interface BaseWorkflowEvent {

    UUID eventId();

    UUID correlationId();

    UUID userId();

    UUID jobId();

    UUID applicationId();

    Instant timestamp();
}
