package ai.careerpilot.applications;

import ai.careerpilot.applications.ApplicationHealthService.HealthResult;
import ai.careerpilot.applications.ApplicationHealthService.HealthStatus;
import ai.careerpilot.domain.Application;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Application Command Center — deterministic "what should I do next" recommendation. Pure rules
 * over status + health + elapsed time, no LLM (matches {@code SafetyEngine}'s disclosed-rule
 * style). The Copilot's narrative text (see
 * {@code ai.careerpilot.service.copilot.skill.ExplainApplicationStatusHandler}) explains this
 * result in prose but never re-derives the underlying decision.
 *
 * <p>Rule order (first match wins):
 * <ol>
 *   <li>Terminal negative status ({@code REJECTED}/{@code WITHDRAWN}) → {@code REAPPLY_LATER}</li>
 *   <li>{@code OFFER} status → {@code WAIT} (decision is the candidate's, not ours)</li>
 *   <li>Active + ATS score &lt; 50 → {@code IMPROVE_RESUME}</li>
 *   <li>{@code SAVED} for &gt;= 7 days, never applied → {@code OUTREACH}</li>
 *   <li>{@code APPLIED} &gt;= 30 days with no movement → {@code WITHDRAW}</li>
 *   <li>{@code APPLIED} &gt;= 14 days with no movement → {@code FOLLOW_UP_NOW}</li>
 *   <li>{@code INTERVIEWING} and health is {@code RISK}/{@code STALE} → {@code NETWORK}</li>
 *   <li>Health {@code RISK} → {@code IMPROVE_COVER_LETTER}</li>
 *   <li>Otherwise → {@code WAIT}</li>
 * </ol>
 */
@Service
public class ApplicationRecommendationService {

    public enum RecommendationAction {
        WAIT, FOLLOW_UP_NOW, WITHDRAW, REAPPLY_LATER, IMPROVE_RESUME, IMPROVE_COVER_LETTER, OUTREACH, NETWORK
    }

    public record RecommendationResult(RecommendationAction action, String reasoning) {}

    public RecommendationResult recommend(Application app, HealthResult health, Instant lastStatusChangeAt) {
        String status = app.getStatus() == null ? "" : app.getStatus();
        Instant reference = lastStatusChangeAt != null ? lastStatusChangeAt : app.getUpdatedAt();
        long daysSinceChange = reference == null ? 0 : Duration.between(reference, Instant.now()).toDays();

        if ("REJECTED".equals(status) || "WITHDRAWN".equals(status)) {
            return new RecommendationResult(RecommendationAction.REAPPLY_LATER,
                    "This application ended in " + status.toLowerCase() + ". Consider reapplying to this company "
                            + "in 3-6 months, or targeting a similar role there once your profile has strengthened.");
        }

        if ("OFFER".equals(status)) {
            return new RecommendationResult(RecommendationAction.WAIT,
                    "You have an offer — review terms and respond by your deadline. No further action needed from us.");
        }

        Integer ats = app.getAtsScore();
        if (ats != null && ats < 50) {
            return new RecommendationResult(RecommendationAction.IMPROVE_RESUME,
                    "ATS score is " + ats + ", below the 50 threshold — tailor your resume's keywords to this job "
                            + "description before (or instead of) following up.");
        }

        if ("SAVED".equals(status) && daysSinceChange >= 7) {
            return new RecommendationResult(RecommendationAction.OUTREACH,
                    "Saved " + daysSinceChange + " days ago with no application yet — apply soon or reach out to "
                            + "someone at the company before the role goes cold.");
        }

        if ("APPLIED".equals(status) && daysSinceChange >= 30) {
            return new RecommendationResult(RecommendationAction.WITHDRAW,
                    "No movement in " + daysSinceChange + " days since applying — consider withdrawing to focus "
                            + "your effort on more responsive opportunities.");
        }

        if ("APPLIED".equals(status) && daysSinceChange >= 14) {
            return new RecommendationResult(RecommendationAction.FOLLOW_UP_NOW,
                    "It's been " + daysSinceChange + " days since you applied with no update — a brief, polite "
                            + "follow-up email is appropriate now.");
        }

        if ("INTERVIEWING".equals(status)
                && (health.status() == HealthStatus.RISK || health.status() == HealthStatus.STALE)) {
            return new RecommendationResult(RecommendationAction.NETWORK,
                    "The interview process has gone quiet — use this window to network with people at the "
                            + "company rather than waiting passively.");
        }

        if (health.status() == HealthStatus.RISK) {
            return new RecommendationResult(RecommendationAction.IMPROVE_COVER_LETTER,
                    "Application health is low (score " + health.score() + ") — strengthening your cover letter's "
                            + "specificity to this role/company may help it stand out.");
        }

        return new RecommendationResult(RecommendationAction.WAIT,
                "Nothing actionable right now — sit tight and revisit if the status changes or it goes stale.");
    }
}
