package ai.careerpilot.careertimeline;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 10H — one row in the unified Career Timeline. Every field is either a direct pass-through
 * of an existing entity's own column, or a plain string label derived from one (e.g. "Mission
 * created" from a mission row's own {@code createdAt}) — never a fabricated narrative. See
 * {@link CareerTimelineService} for exactly how each category maps to real source data.
 */
public record CareerTimelineEntry(
        UUID id,
        CareerTimelineCategory category,
        String eventType,
        String title,
        String description,
        Instant occurredAt,
        String source,
        Boolean aiGenerated,
        UUID relatedMissionId,
        UUID relatedJobId,
        UUID relatedCompanyId,
        String relatedCompanyName) {
}
