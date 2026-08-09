package ai.careerpilot.execution.timeline;

import ai.careerpilot.domain.ExecutionStageEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * P7 Action 7 — Execution Visibility. Derives the "what did automation actually do" truth model
 * (category A — automation execution evidence, never Guided Apply readiness or human-action facts,
 * which stay owned by {@code GuidedApplyBriefService}/{@code ApplicationSubmissionSessionService})
 * purely from the stage rows {@link ExecutionTimelineService} already assembles. No new query, no
 * new table — every field here is read back from data {@code ExecutionStageEvent} already records.
 *
 * <p>Every boolean/count is either proven by a specific stage's presence, or explicitly {@code
 * null} ("unknown" — instrumentation off, or the run never reached that point) — never guessed.
 * There is deliberately no "fields verified" count: no stage anywhere in this platform records a
 * per-field verification number (submission verification is a single pass/fail confirmation, not
 * a per-field one) — inventing one here would be exactly the fabricated metric this file's own
 * design principle forbids.
 */
@Service
public class ExecutionEvidenceService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEvidenceService.class);

    private final ExecutionTimelineService timelineService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExecutionEvidenceService(ExecutionTimelineService timelineService) {
        this.timelineService = timelineService;
    }

    /** No execution has ever been created for this application yet — never a guess at readiness. */
    public Map<String, Object> notStarted() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hasExecution", false);
        out.put("state", "NOT_STARTED");
        out.put("automationStarted", false);
        out.put("instrumentationEnabled", null);
        out.put("timeline", List.of());
        return out;
    }

    public Map<String, Object> evidenceFor(UUID userId, UUID executionId) {
        if (executionId == null) return notStarted();

        Optional<Map<String, Object>> maybe = timelineService.timeline(executionId, userId);
        if (maybe.isEmpty()) return notStarted();

        Map<String, Object> full = maybe.get();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stages = (List<Map<String, Object>>) full.getOrDefault("stages", List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> exit = (Map<String, Object>) full.getOrDefault("exit", Map.of());
        boolean instrumentationEnabled = Boolean.TRUE.equals(full.get("instrumentationEnabled"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hasExecution", true);
        out.put("executionId", executionId);
        out.put("executionStatus", full.get("executionStatus"));
        out.put("instrumentationEnabled", instrumentationEnabled);
        out.put("automationStarted", !stages.isEmpty());

        // Explicitly null (UNKNOWN), never false, when there are no stages at all — "never started"
        // and "started but instrumentation couldn't prove it" are different facts.
        out.put("employerPageReached", stages.isEmpty() ? null : hasCompletedStage(stages, "NAVIGATION_COMPLETED"));
        out.put("formDiscovered", stages.isEmpty() ? null : hasCompletedStage(stages, "FORM_DISCOVERED"));
        // A single field, not separate captchaDetected/loginDetected booleans: CaptchaLoginDetector
        // (protected — see P7 Action 7's scope constraints) genuinely returns one combined boolean
        // and the recorded reason text is the same fixed literal either way ("captcha or login wall
        // detected"), so there is no real signal to honestly split "which one" from. Reporting two
        // independent booleans derived from that one literal would fabricate a distinction the
        // backend does not actually prove.
        out.put("captchaOrLoginDetected", stages.isEmpty() ? null : hasFailureReasonContaining(stages, "PAGE_CLASSIFIED", "captcha or login"));

        out.put("fieldsDiscovered", detailNumber(stages, "FORM_DISCOVERED", "fieldsDiscovered"));
        Integer connectorFilled = detailNumber(stages, "FIELD_FILL_STARTED", "connectorFieldsFilled");
        Integer engineFilled = detailNumber(stages, "FIELD_FILL_COMPLETED", "engineFieldsFilled");
        out.put("fieldsFilled", sumOrNull(connectorFilled, engineFilled));
        out.put("fieldsResolved", detailNumber(stages, "QUESTIONS_RESOLVED", "resolvedCount"));
        out.put("fieldsUnresolved", detailNumber(stages, "QUESTIONS_RESOLVED", "unresolvedCount"));

        String outcome = String.valueOf(exit.get("outcome"));
        boolean automationStopped = !stages.isEmpty() && !"COMPLETED".equals(outcome) && !"IN_FLIGHT".equals(outcome);
        out.put("automationStopped", automationStopped);
        out.put("stopReason", exit.get("reason"));
        out.put("stoppedAtStage", exit.get("stoppedAtDisplayName"));
        out.put("failureCategory", exit.get("failureCategory"));

        out.put("state", deriveState(stages, outcome));
        out.put("timeline", stages);
        return out;
    }

    /**
     * The Phase 4 coarse state model. {@code IN_FLIGHT} (an open last stage) resolves to the
     * furthest genuinely-completed milestone rather than a bare "STARTED", since a caller mid-run
     * still deserves to know how far it got.
     */
    private String deriveState(List<Map<String, Object>> stages, String outcome) {
        if (stages.isEmpty()) return "NOT_STARTED";
        if ("COMPLETED".equals(outcome)) return "COMPLETED";
        if ("IN_FLIGHT".equals(outcome)) {
            if (hasCompletedStage(stages, "FORM_DISCOVERED")) return "FILLING";
            if (hasCompletedStage(stages, "NAVIGATION_COMPLETED")) return "EMPLOYER_PAGE_REACHED";
            return "STARTED";
        }
        // Terminal but not COMPLETED — genuinely stopped somewhere.
        boolean fillStarted = hasCompletedStage(stages, "FIELD_FILL_STARTED");
        boolean fillCompleted = hasCompletedStage(stages, "FIELD_FILL_COMPLETED");
        if (fillStarted && !fillCompleted) return "PARTIALLY_FILLED";
        if (hasCompletedStage(stages, "FORM_DISCOVERED")) return "STOPPED";
        if (hasCompletedStage(stages, "NAVIGATION_COMPLETED")) return "EMPLOYER_PAGE_REACHED";
        return "STOPPED";
    }

    private boolean hasCompletedStage(List<Map<String, Object>> stages, String stageName) {
        return stages.stream().anyMatch(s -> stageName.equals(s.get("stage"))
                && ExecutionStageEvent.STATUS_COMPLETED.equals(s.get("status")));
    }

    private boolean hasFailureReasonContaining(List<Map<String, Object>> stages, String stageName, String needle) {
        return stages.stream().anyMatch(s -> stageName.equals(s.get("stage"))
                && ExecutionStageEvent.STATUS_FAILED.equals(s.get("status"))
                && s.get("reason") != null
                && String.valueOf(s.get("reason")).toLowerCase(Locale.ROOT).contains(needle));
    }

    private Integer detailNumber(List<Map<String, Object>> stages, String stageName, String key) {
        for (Map<String, Object> s : stages) {
            if (!stageName.equals(s.get("stage"))) continue;
            Object detail = s.get("detail");
            if (!(detail instanceof String json) || json.isBlank()) continue;
            try {
                Map<?, ?> parsed = mapper.readValue(json, Map.class);
                Object v = parsed.get(key);
                if (v instanceof Number n) return n.intValue();
            } catch (Exception e) {
                log.warn("EXECUTION_EVIDENCE detail parse failed for stage {}: {}", stageName, e.toString());
            }
        }
        return null;
    }

    private Integer sumOrNull(Integer a, Integer b) {
        if (a == null && b == null) return null;
        return (a == null ? 0 : a) + (b == null ? 0 : b);
    }
}
