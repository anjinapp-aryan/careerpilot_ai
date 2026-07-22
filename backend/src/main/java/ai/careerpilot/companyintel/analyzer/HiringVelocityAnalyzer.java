package ai.careerpilot.companyintel.analyzer;

import ai.careerpilot.domain.CompanyTimelineEvent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7.17.3 — extends the existing Hiring Intelligence analyzers ({@code HiringPatternAnalyzer},
 * {@code CompanyTrendAnalyzer}) with time-to-hire, computed from real {@code CompanyTimelineEvent}
 * timestamps rather than a new engine. Pairs each company's {@code APPLICATION_SUBMITTED} event with
 * the {@code OFFER_RECEIVED} event sharing the same {@code refId} (the job id — confirmed to be how
 * {@code CompanyKnowledgeWorker} already stamps these events).
 *
 * <p>Department-level hiring and seasonal hiring are requested by the spec but have NO backing data
 * source anywhere in this codebase (no department dimension exists on {@code CompanyRelationship} or
 * {@code Job}; no month/quarter bucketing exists in this pipeline) — {@link #metrics} reports them as
 * {@code null} with an explicit note rather than inventing a number. Hiring trend/velocity/momentum
 * are NOT recomputed here — they already exist in {@code CompanyTrendAnalyzer}; reuse that directly.
 */
@Component
public class HiringVelocityAnalyzer {

    public Optional<CompanyInsight> analyze(List<CompanyTimelineEvent> events) {
        Map<String, Object> m = metrics(events);
        Double avgDays = (Double) m.get("avgTimeToHireDays");
        if (avgDays == null) return Optional.empty();
        return Optional.of(new CompanyInsight("HIRING_VELOCITY",
                "Average time to hire: " + Math.round(avgDays) + " days",
                (String) m.get("avgTimeToHireEvidence")));
    }

    /** Structured metrics for the Company Dashboard / API — see class javadoc for honesty notes. */
    public Map<String, Object> metrics(List<CompanyTimelineEvent> events) {
        Map<UUID, Instant> applied = new HashMap<>();
        Map<UUID, Instant> offered = new HashMap<>();
        for (CompanyTimelineEvent e : events) {
            if (e.getRefId() == null || e.getOccurredAt() == null) continue;
            if ("APPLICATION_SUBMITTED".equals(e.getEventType())) {
                applied.merge(e.getRefId(), e.getOccurredAt(), (a, b) -> a.isBefore(b) ? a : b);
            } else if ("OFFER_RECEIVED".equals(e.getEventType())) {
                offered.merge(e.getRefId(), e.getOccurredAt(), (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        List<Long> hireDurationDays = new ArrayList<>();
        for (Map.Entry<UUID, Instant> entry : offered.entrySet()) {
            Instant appliedAt = applied.get(entry.getKey());
            if (appliedAt != null && entry.getValue().isAfter(appliedAt)) {
                hireDurationDays.add(Duration.between(appliedAt, entry.getValue()).toDays());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        Double avg = hireDurationDays.isEmpty() ? null
                : hireDurationDays.stream().mapToLong(Long::longValue).average().orElse(0.0);
        out.put("avgTimeToHireDays", avg);
        out.put("avgTimeToHireEvidence", hireDurationDays.size()
                + " application→offer pairs observed for this company (matched by job id)");
        out.put("departmentHiring", null);
        out.put("departmentHiringNote", "not computed — no department dimension exists on CompanyRelationship or Job");
        out.put("seasonalHiring", null);
        out.put("seasonalHiringNote", "not computed — no month/quarter bucketing exists in this pipeline yet");
        return out;
    }
}
