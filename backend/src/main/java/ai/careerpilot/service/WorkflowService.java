package ai.careerpilot.service;

import ai.careerpilot.agent.AgentServiceClient;
import ai.careerpilot.api.dto.AgentServiceDtos.AgentRunResponse;
import ai.careerpilot.api.dto.WorkflowDtos;
import ai.careerpilot.api.dto.WorkflowDtos.WorkflowRunResponse;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.domain.WorkflowRun;
import ai.careerpilot.kafka.WorkflowEventProducer;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.repo.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final AgentServiceClient agent;
    private final ResumeRepository resumes;
    private final JobRepository jobs;
    private final WorkflowRunRepository runs;
    private final WorkflowEventProducer events;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkflowService(AgentServiceClient agent, ResumeRepository resumes, JobRepository jobs,
                           WorkflowRunRepository runs, WorkflowEventProducer events) {
        this.agent = agent;
        this.resumes = resumes;
        this.jobs = jobs;
        this.runs = runs;
        this.events = events;
    }

    public record StartWorkflowRequest(
            UUID resumeId,
            List<UUID> jobIds,
            String targetRole,
            String targetSeniority,
            List<String> targetLocations) {}

    @Transactional
    public WorkflowRun start(UUID userId, UUID orgId, StartWorkflowRequest req) {
        log.info("Workflow Created: user={}, target_role={}, jobs={}", userId, req.targetRole(), req.jobIds().size());

        Resume resume = resumes.findById(req.resumeId()).orElseThrow();
        if (!resume.getUserId().equals(userId)) throw new SecurityException("forbidden");

        List<Map<String, Object>> jobPayload = new ArrayList<>();
        for (UUID id : req.jobIds()) {
            Job j = jobs.findById(id).orElseThrow();
            jobPayload.add(Map.of(
                    "id", j.getId().toString(),
                    "title", j.getTitle(),
                    "company", j.getCompany(),
                    "description", j.getDescription(),
                    "location", j.getLocation() == null ? "" : j.getLocation(),
                    "salary", j.getSalaryRange() == null ? "" : j.getSalaryRange()));
        }

        Map<String, Object> body = new HashMap<>();
        body.put("user_id", userId.toString());
        body.put("org_id", orgId.toString());
        body.put("resume_text", resume.getParsedText() == null ? "" : resume.getParsedText());
        body.put("target_role", req.targetRole());
        body.put("target_seniority", req.targetSeniority());
        body.put("target_locations", req.targetLocations());
        body.put("job_descriptions", jobPayload);

        log.info("Workflow Started: calling agent service with {} jobs", jobPayload.size());
        AgentRunResponse resp = agent.startRun(body);
        log.info("Workflow Agent Response: thread_id={}, status={}", resp.thread_id(), resp.status());
        return persistFromResponse(userId, orgId, req, resp);
    }

    @Transactional
    public WorkflowRun resume(UUID userId, String actor, String threadId, String decision, String feedback) {
        WorkflowRun run = runs.findByThreadId(threadId).orElseThrow();
        if (!run.getUserId().equals(userId)) throw new SecurityException("forbidden");

        // Invalid-state / double-submit guard (Scenario E/F): a run may be resumed ONLY
        // while it is genuinely awaiting approval. We check the DERIVED status — the exact
        // value the UI rendered — so the guard can never disagree with what the user saw.
        // This short-circuits illegal transitions (approve-a-completed-run, reject-a-rejected-run,
        // approve-a-rejected-run, …) with a 409 before any agent round-trip, and the
        // agent-service repeats the check atomically against its checkpoint for the
        // true-concurrency window.
        String current = deriveDisplayStatus(run);
        if (!"INTERRUPTED".equals(current)) {
            throw new IllegalStateException("Workflow is not awaiting approval (status=" + current + ")");
        }

        AgentRunResponse resp = agent.resumeRun(threadId, decision, feedback);
        return mergeResponse(run, resp, actor, decision, feedback);
    }

    public WorkflowRun get(UUID userId, String threadId) {
        WorkflowRun run = runs.findByThreadId(threadId).orElseThrow();
        if (!run.getUserId().equals(userId)) throw new SecurityException("forbidden");
        return run;
    }

    public List<WorkflowRun> recent(UUID userId) {
        return runs.findTop20ByUserIdOrderByCreatedAtDesc(userId);
    }

    private WorkflowRun persistFromResponse(UUID userId, UUID orgId, StartWorkflowRequest req, AgentRunResponse resp) {
        String threadId = resp.thread_id();
        String status = mapStatus(resp.status());
        Map<String, Object> state = resp.state();

        WorkflowRun run = runs.findByThreadId(threadId).orElseGet(() -> WorkflowRun.builder()
                .userId(userId).orgId(orgId).threadId(threadId)
                .targetRole(req.targetRole()).targetSeniority(req.targetSeniority())
                .status(status).state("{}").build());

        return mergeState(run, status, state);
    }

    private WorkflowRun mergeResponse(WorkflowRun run, AgentRunResponse resp,
                                      String actor, String decision, String feedback) {
        // Audit trail (Phase 6, Option A — no schema change): stamp who/when the decision
        // was made directly into the workflow state blob so it persists and is exposed on
        // every read via toResponse(). approved_* and rejected_* are mutually exclusive.
        Map<String, Object> state = new HashMap<>(resp.state() == null ? Map.of() : resp.state());
        String nowIso = java.time.Instant.now().toString();
        if ("rejected".equalsIgnoreCase(decision == null ? "" : decision.trim())) {
            state.put("rejected_by", actor);
            state.put("rejected_at", nowIso);
        } else {
            state.put("approved_by", actor);
            state.put("approved_at", nowIso);
        }
        if (feedback != null && !feedback.isBlank()) {
            state.put("human_feedback", feedback);
        }
        return mergeState(run, mapStatus(resp.status()), state);
    }

    private WorkflowRun mergeState(WorkflowRun run, String status, Map<String, Object> state) {
        log.info("mergeState_enter: thread={}, state_keys={}", run.getThreadId(), state.keySet());
        run.setStatus(status);
        run.setResumeScore(intOrNull(state, "resume_score"));
        run.setJobMatchScore(intOrNull(state, "job_match_score"));
        run.setAtsScore(intOrNull(state, "ats_score"));
        run.setInterviewReadinessScore(intOrNull(state, "interview_readiness_score"));
        try {
            String stateJson = mapper.writeValueAsString(state);
            log.info("state_serialized: thread={}, json_length={}", run.getThreadId(), stateJson.length());
            run.setState(stateJson);
            log.debug("Workflow State Persisted: thread={}, status={}", run.getThreadId(), status);
        } catch (Exception e) {
            log.error("Failed to serialize workflow state: thread={}, error_type={}, error={}",
                    run.getThreadId(), e.getClass().getSimpleName(), e.getMessage(), e);
            run.setState("{}");
        }
        WorkflowRun saved = runs.save(run);
        log.info("Workflow Updated: thread={}, status={}", saved.getThreadId(), saved.getStatus());
        events.publish(saved.getThreadId(),
                Map.of("threadId", saved.getThreadId(), "status", saved.getStatus(), "userId", saved.getUserId().toString()));
        return saved;
    }

    private Integer intOrNull(Map<String, Object> state, String field) {
        Object v = state.get(field);
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Number) return ((Number) v).intValue();
        return null;
    }

    private String mapStatus(String s) {
        return switch (s) {
            case "interrupted" -> "INTERRUPTED";
            case "completed" -> "COMPLETED";
            case "rejected" -> "REJECTED";
            case "error" -> "ERROR";
            default -> "RUNNING";
        };
    }

    // ---- Response mapping ----

    // Display order of pipeline stages. The 7 agent stages are driven by the
    // agent_execution telemetry the agent-service appends per node; human_approval
    // is derived from awaiting_human_approval (it is intentionally not instrumented).
    private static final String[][] PIPELINE_STAGES = {
        {"resume_intelligence", "Resume Intelligence"},
        {"job_discovery", "Job Discovery"},
        {"ats_optimization", "ATS Optimization"},
        {"interview_prep", "Interview Preparation"},
        {"career_strategy", "Career Strategy"},
        {"salary_intelligence", "Salary Intelligence"},
        {"human_approval", "Human Approval"},
        {"application_tracking", "Application Tracking"}
    };

    /**
     * Build the execution timeline — the SINGLE source of workflow truth — from the
     * agent-service's {@code agent_execution} telemetry (status/provider/duration/
     * completed_at per stage). Stages without a telemetry entry are PENDING.
     *
     * <p>The approval gate is the part that used to lie. {@code human_approval} is
     * derived from the {@code human_decision}/{@code awaiting_human_approval} flags,
     * but ONLY once the pipeline has actually <em>reached</em> the gate — i.e. all six
     * upstream agent stages COMPLETED. Without that guard a stale {@code human_decision}
     * (e.g. an Approve on a run that had already failed at resume_intelligence) marked
     * Human Approval COMPLETED while the upstream stages were still PENDING — the
     * "impossible state". A failed upstream stage also forbids the gate from advancing
     * and forces everything downstream to PENDING.
     */
    private List<WorkflowDtos.WorkflowAgent> extractAgentTimeline(Map<String, Object> state) {
        Map<String, Map<String, Object>> byStage = indexExecutionByStage(state);
        boolean awaiting = Boolean.TRUE.equals(state.get("awaiting_human_approval"));
        Object decision = state.get("human_decision");
        String decisionStr = decision instanceof String s ? s.trim().toLowerCase() : "";

        List<WorkflowDtos.WorkflowAgent> agents = new ArrayList<>();
        // Did every agent stage BEFORE the approval gate actually complete? Only then
        // may the gate reflect a decision/await. A gap or a failure upstream means the
        // gate was never reached, so it (and everything after it) stays PENDING.
        boolean reachedApproval = true;
        boolean upstreamFailed = false;

        for (String[] stage : PIPELINE_STAGES) {
            String stageKey = stage[0];
            String displayName = stage[1];

            if ("human_approval".equals(stageKey)) {
                String status;
                if (upstreamFailed || !reachedApproval) {
                    status = "PENDING";                       // gate never reached
                } else if (awaiting) {
                    status = "WAITING_FOR_APPROVAL";
                } else if ("rejected".equals(decisionStr)) {
                    status = "REJECTED";
                } else if (!decisionStr.isBlank()) {
                    status = "COMPLETED";
                } else {
                    status = "PENDING";
                }
                agents.add(new WorkflowDtos.WorkflowAgent(displayName, status, null, null, null));
                continue;
            }

            Map<String, Object> entry = byStage.get(stageKey);
            String status;
            if (entry != null) {
                status = statusOf(entry);                     // telemetry: COMPLETED or FAILED
            } else if (stageProducedOutput(state, stageKey)) {
                // Legacy heal: runs created before execution telemetry existed have no
                // agent_execution entries but DO carry each stage's output in state.
                // A non-empty output proves the stage ran — so a legacy COMPLETED run
                // renders a fully-COMPLETED timeline instead of an all-PENDING one.
                status = "COMPLETED";
            } else {
                status = "PENDING";                           // genuinely has not run
            }

            if ("application_tracking".equals(stageKey)) {     // post-gate stage
                agents.add(entry != null ? toAgent(displayName, status, entry)
                        : new WorkflowDtos.WorkflowAgent(displayName, status, null, null, null));
                continue;
            }

            // Pre-gate stage: it must be COMPLETED for the approval gate to be reachable.
            if ("FAILED".equals(status)) {
                upstreamFailed = true;
                reachedApproval = false;
            } else if (!"COMPLETED".equals(status)) {
                reachedApproval = false;
            }
            agents.add(entry != null ? toAgent(displayName, status, entry)
                    : new WorkflowDtos.WorkflowAgent(displayName, status, null, null, null));
        }
        return agents;
    }

    /** Status recorded on an execution telemetry entry, defaulting to COMPLETED. */
    private String statusOf(Map<String, Object> entry) {
        String status = asString(entry.get("status"));
        return (status == null || status.isBlank()) ? "COMPLETED" : status;
    }

    /**
     * True when {@code state} carries a non-empty output for {@code stageKey} — proof the
     * stage ran on a legacy run that predates execution telemetry. Keys are chosen so the
     * error/empty path (e.g. resume_intelligence returns {@code candidate_profile={}}) does
     * NOT register as completed.
     */
    private static boolean stageProducedOutput(Map<String, Object> state, String stageKey) {
        return switch (stageKey) {
            case "resume_intelligence" -> nonEmpty(state.get("candidate_profile")) || nonEmpty(state.get("extracted_skills"));
            case "job_discovery" -> nonEmpty(state.get("ranked_jobs"));
            case "ats_optimization" -> nonEmpty(state.get("ats_optimization_plan")) || nonEmpty(state.get("missing_keywords"));
            case "interview_prep" -> nonEmpty(state.get("interview_plan"));
            case "career_strategy" -> nonEmpty(state.get("career_roadmap"));
            case "salary_intelligence" -> nonEmpty(state.get("salary_insights"));
            case "application_tracking" -> nonEmpty(state.get("tracked_application"));
            default -> false;
        };
    }

    private static boolean nonEmpty(Object v) {
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        if (v instanceof List<?> l) return !l.isEmpty();
        if (v instanceof String s) return !s.isBlank();
        return v != null;
    }

    private WorkflowDtos.WorkflowAgent toAgent(String displayName, String status, Map<String, Object> entry) {
        return new WorkflowDtos.WorkflowAgent(
                displayName,
                status,
                asString(entry.get("completed_at")),
                asString(entry.get("provider")),
                asLong(entry.get("duration_ms")));
    }

    /**
     * Derive the run-level status as a <em>pure function of the agent timeline</em>
     * (plus the cross-cutting {@code errors} marker). This is what makes the top
     * pipeline badge and the execution timeline impossible to disagree: both read the
     * same {@code agents[]}. It also encodes the legal state machine — a failed run can
     * never report COMPLETED, and a failed/errored run never offers approval.
     */
    private String deriveRunStatus(List<WorkflowDtos.WorkflowAgent> agents, Map<String, Object> state, String persisted) {
        boolean anyFailed = agents.stream().anyMatch(a -> "FAILED".equals(a.status()));
        boolean hasErrors = state.get("errors") instanceof List<?> l && !l.isEmpty();
        if (anyFailed || hasErrors) return "FAILED";          // RUNNING → FAILED; forbids FAILED → COMPLETED
        if (agents.stream().anyMatch(a -> "WAITING_FOR_APPROVAL".equals(a.status()))) return "INTERRUPTED";
        if (agents.stream().anyMatch(a -> "REJECTED".equals(a.status()))) return "REJECTED";
        if (agents.stream().allMatch(a -> "COMPLETED".equals(a.status()))) return "COMPLETED";
        // Indeterminate (legacy/partial telemetry, some stages still PENDING): trust the
        // persisted terminal status. INTERRUPTED only ever comes from a real
        // WAITING_FOR_APPROVAL stage above, so a persisted "INTERRUPTED" that reached here
        // has no live gate and is treated as FAILED (not resurrected as approvable).
        return switch (persisted == null ? "" : persisted) {
            case "COMPLETED" -> "COMPLETED";
            case "REJECTED" -> "REJECTED";
            case "ERROR", "FAILED", "INTERRUPTED" -> "FAILED";
            default -> "RUNNING";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> indexExecutionByStage(Map<String, Object> state) {
        Map<String, Map<String, Object>> byStage = new HashMap<>();
        Object raw = state.get("agent_execution");
        if (!(raw instanceof List<?> list)) return byStage;
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Object stage = m.get("stage");
                if (stage instanceof String s) {
                    // Last write wins — a stage re-run overwrites the earlier entry.
                    byStage.put(s, (Map<String, Object>) m);
                }
            }
        }
        return byStage;
    }

    private String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private Long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        return null;
    }

    /** Parse the persisted JSON state blob into a Map, tolerant of null/empty/corrupt. */
    private Map<String, Object> parseState(WorkflowRun run) {
        if (run.getState() == null || run.getState().isEmpty()) return new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(run.getState(), Map.class);
            return parsed;
        } catch (Exception e) {
            log.error("Failed to parse workflow state for {}: error_type={}, error={}",
                    run.getThreadId(), e.getClass().getSimpleName(), e.getMessage(), e);
            return new HashMap<>();
        }
    }

    /**
     * The lifecycle status as DERIVED from the agent timeline — identical to what
     * {@link #toResponse} returns and the UI renders. Exposed so other read paths (e.g.
     * the Copilot and the resume guard) report the same status the user sees, never the
     * raw persisted {@code run.status} column.
     */
    public String deriveDisplayStatus(WorkflowRun run) {
        Map<String, Object> stateMap = parseState(run);
        List<WorkflowDtos.WorkflowAgent> agents = extractAgentTimeline(stateMap);
        return deriveRunStatus(agents, stateMap, run.getStatus());
    }

    private static String auditField(Map<String, Object> state, String key) {
        Object v = state.get(key);
        return v == null ? null : v.toString();
    }

    public WorkflowRunResponse toResponse(WorkflowRun run) {
        log.info("toResponse_enter: thread={}", run.getThreadId());
        Map<String, Object> stateMap = new HashMap<>();
        try {
            if (run.getState() != null && !run.getState().isEmpty()) {
                log.debug("state_json_length: thread={}, length={}", run.getThreadId(), run.getState().length());
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.readValue(run.getState(), Map.class);
                log.info("state_deserialized: thread={}, state_keys={}", run.getThreadId(), parsed.keySet());
                stateMap = parsed;
            }
        } catch (Exception e) {
            log.error("Failed to parse workflow state for {}: error_type={}, error={}",
                    run.getThreadId(), e.getClass().getSimpleName(), e.getMessage(), e);
        }
        try {
            List<WorkflowDtos.WorkflowAgent> agents = extractAgentTimeline(stateMap);
            // Single source of truth: the response status is DERIVED from the same
            // agents[] the UI renders, not the independently-persisted run.status.
            // This guarantees the top pipeline badge and the timeline never diverge,
            // and heals any already-persisted "impossible state" rows on read.
            String derivedStatus = deriveRunStatus(agents, stateMap, run.getStatus());
            WorkflowRunResponse response = new WorkflowRunResponse(
                    run.getId(),
                    run.getThreadId(),
                    derivedStatus,
                    run.getTargetRole(),
                    run.getTargetSeniority(),
                    run.getResumeScore(),
                    run.getJobMatchScore(),
                    run.getAtsScore(),
                    run.getInterviewReadinessScore(),
                    stateMap,
                    agents,
                    run.getErrorMessage(),
                    run.getCreatedAt(),
                    run.getUpdatedAt(),
                    auditField(stateMap, "approved_by"),
                    auditField(stateMap, "approved_at"),
                    auditField(stateMap, "rejected_by"),
                    auditField(stateMap, "rejected_at"),
                    auditField(stateMap, "human_feedback"));
            log.info("response_created: thread={}, response_state_keys={}, agents_count={}", run.getThreadId(), stateMap.keySet(), agents.size());
            return response;
        } catch (Exception e) {
            log.error("Failed to create response for {}: error_type={}, error={}",
                    run.getThreadId(), e.getClass().getSimpleName(), e.getMessage(), e);
            throw e;
        }
    }

    public List<WorkflowRunResponse> toResponseList(List<WorkflowRun> runs) {
        return runs.stream().map(this::toResponse).toList();
    }
}
