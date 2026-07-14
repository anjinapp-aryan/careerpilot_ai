package ai.careerpilot.applications;

import ai.careerpilot.domain.Application;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Application Command Center — deterministic application-health scoring. No LLM, no external
 * calls: pure arithmetic over signals already on the {@link Application} row (or its lifecycle,
 * when {@code workflow.tracking.enabled}). Mirrors the disclosed-formula style of
 * {@link ai.careerpilot.learning.career.CareerProbabilityEngine} and
 * {@code ai.careerpilot.execution.safety.SafetyEngine}.
 *
 * <p><b>Formula</b> — start at a base score of 50, then:
 * <ul>
 *   <li>Match score: +20 for &gt;=80, +10 for 60-79, -10 for &lt;40 (null = no adjustment)</li>
 *   <li>ATS score: +15 for &gt;=80, +5 for 60-79, -15 for &lt;40 (null = no adjustment)</li>
 *   <li>Staleness: -5 per full week since the last status change beyond an initial 2-week grace
 *       period, capped at -30 (only applied while the application is still in an active status)</li>
 *   <li>Status bonus: +20 for {@code OFFER}, +10 for {@code INTERVIEWING}, -30 for
 *       {@code REJECTED}/{@code WITHDRAWN}</li>
 * </ul>
 * The resulting score (clamped 0-100) buckets into one of six statuses:
 * <ul>
 *   <li>{@code COLD} — terminal negative outcome ({@code REJECTED}/{@code WITHDRAWN}), regardless of score</li>
 *   <li>{@code STALE} — active status with &gt;21 days since the last status change</li>
 *   <li>{@code EXCELLENT} — score &gt;= 80</li>
 *   <li>{@code HEALTHY} — score &gt;= 60</li>
 *   <li>{@code NEEDS_ATTENTION} — score &gt;= 40</li>
 *   <li>{@code RISK} — score &lt; 40</li>
 * </ul>
 */
@Service
public class ApplicationHealthService {

    private static final java.util.Set<String> TERMINAL_NEGATIVE = java.util.Set.of("REJECTED", "WITHDRAWN");

    public record HealthResult(HealthStatus status, int score, String reasoning) {}

    public enum HealthStatus { EXCELLENT, HEALTHY, NEEDS_ATTENTION, RISK, COLD, STALE }

    /**
     * @param app                the application row
     * @param lastStatusChangeAt most recent status transition time; falls back to
     *                           {@code app.getUpdatedAt()} when lifecycle tracking has no history
     */
    public HealthResult evaluate(Application app, Instant lastStatusChangeAt) {
        Instant reference = lastStatusChangeAt != null ? lastStatusChangeAt : app.getUpdatedAt();
        Instant now = Instant.now();
        long daysSinceChange = reference == null ? 0 : Duration.between(reference, now).toDays();

        StringBuilder reasons = new StringBuilder();
        int score = 50;
        reasons.append("Base 50");

        Integer match = app.getMatchScore();
        if (match != null) {
            if (match >= 80) { score += 20; reasons.append("; +20 strong match (").append(match).append(")"); }
            else if (match >= 60) { score += 10; reasons.append("; +10 decent match (").append(match).append(")"); }
            else if (match < 40) { score -= 10; reasons.append("; -10 weak match (").append(match).append(")"); }
        }

        Integer ats = app.getAtsScore();
        if (ats != null) {
            if (ats >= 80) { score += 15; reasons.append("; +15 strong ATS score (").append(ats).append(")"); }
            else if (ats >= 60) { score += 5; reasons.append("; +5 decent ATS score (").append(ats).append(")"); }
            else if (ats < 40) { score -= 15; reasons.append("; -15 weak ATS score (").append(ats).append(")"); }
        }

        String status = app.getStatus() == null ? "" : app.getStatus();
        boolean terminalNegative = TERMINAL_NEGATIVE.contains(status);

        if (!terminalNegative && daysSinceChange > 14) {
            long staleWeeks = (daysSinceChange - 14) / 7 + 1;
            int penalty = (int) Math.min(30, staleWeeks * 5);
            score -= penalty;
            reasons.append("; -").append(penalty).append(" stale (").append(daysSinceChange).append(" days since last change)");
        }

        if ("OFFER".equals(status)) { score += 20; reasons.append("; +20 offer in hand"); }
        else if ("INTERVIEWING".equals(status)) { score += 10; reasons.append("; +10 actively interviewing"); }
        else if (terminalNegative) { score -= 30; reasons.append("; -30 ").append(status.toLowerCase()); }

        score = Math.max(0, Math.min(100, score));

        HealthStatus bucket;
        if (terminalNegative) {
            bucket = HealthStatus.COLD;
        } else if (daysSinceChange > 21) {
            bucket = HealthStatus.STALE;
        } else if (score >= 80) {
            bucket = HealthStatus.EXCELLENT;
        } else if (score >= 60) {
            bucket = HealthStatus.HEALTHY;
        } else if (score >= 40) {
            bucket = HealthStatus.NEEDS_ATTENTION;
        } else {
            bucket = HealthStatus.RISK;
        }

        return new HealthResult(bucket, score, reasons.toString());
    }
}
