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
    private final ResumeVersionService resumeVersions;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkflowService(AgentServiceClient agent, ResumeRepository resumes, JobRepository jobs,
                           WorkflowRunRepository runs, WorkflowEventProducer events,
                           ResumeVersionService resumeVersions) {
        this.agent = agent;
        this.resumes = resumes;
        this.jobs = jobs;
        this.runs = runs;
        this.events = events;
        this.resumeVersions = resumeVersions;
    }

    /**
     * The five core fields drive the default (full_career) workflow. The trailing four are
     * optional and only consumed by the RESUME_OPTIMIZATION template — they are null on a
     * normal workflow run, so the existing Workflow page is unaffected.
     */
    public record StartWorkflowRequest(
            UUID resumeId,
            List<UUID> jobIds,
            String targetRole,
            String targetSeniority,
            List<String> targetLocations,
            String workflowType,        // "RESUME_OPTIMIZATION" or null/"full_career"
            String optimizationMode,    // e.g. "enterprise_architect" (resume_optimization only)
            String jobDescriptionText,  // mode "upload_jd": pasted JD text
            UUID jobId) {}              // mode "select_job": an existing job to target

    private static final String WORKFLOW_TYPE_RESUME_OPT = "resume_optimization";

    private boolean isResumeOptimization(StartWorkflowRequest req) {
        return WORKFLOW_TYPE_RESUME_OPT.equalsIgnoreCase(
                req.workflowType() == null ? "" : req.workflowType().trim());
    }

    @Transactional
    public WorkflowRun start(UUID userId, UUID orgId, StartWorkflowRequest req) {
        boolean resumeOpt = isResumeOptimization(req);
        log.info("Workflow Created: user={}, type={}, target_role={}", userId,
                resumeOpt ? WORKFLOW_TYPE_RESUME_OPT : "full_career", req.targetRole());

        Resume resume = resumes.findById(req.resumeId()).orElseThrow();
        if (!resume.getUserId().equals(userId)) throw new SecurityException("forbidden");

        // RESUME_OPTIMIZATION supplies a single (synthetic, pasted, or selected) target JD;
        // full_career uses the user-selected jobs exactly as before.
        List<Map<String, Object>> jobPayload = resumeOpt
                ? buildOptimizationJobs(orgId, req)
                : buildSelectedJobs(req.jobIds());

        // For resume optimization, default the displayed target role to the mode title.
        String targetRole = req.targetRole();
        if (resumeOpt && (targetRole == null || targetRole.isBlank()) && !jobPayload.isEmpty()) {
            targetRole = String.valueOf(jobPayload.get(0).get("title"));
        }

        Map<String, Object> body = new HashMap<>();
        body.put("user_id", userId.toString());
        body.put("org_id", orgId.toString());
        body.put("resume_text", resume.getParsedText() == null ? "" : resume.getParsedText());
        body.put("target_role", targetRole == null ? "" : targetRole);
        body.put("target_seniority", req.targetSeniority() == null ? "" : req.targetSeniority());
        body.put("target_locations", req.targetLocations() == null ? List.of() : req.targetLocations());
        body.put("job_descriptions", jobPayload);
        body.put("workflow_type", resumeOpt ? WORKFLOW_TYPE_RESUME_OPT : "full_career");
        body.put("optimization_mode", req.optimizationMode() == null ? "" : req.optimizationMode());

        log.info("Workflow Started: calling agent service with {} jobs", jobPayload.size());
        AgentRunResponse resp = agent.startRun(body);
        log.info("Workflow Agent Response: thread_id={}, status={}", resp.thread_id(), resp.status());
        return persistFromResponse(userId, orgId, req, resp);
    }

    /** Build the agent job payload from the user's explicitly selected jobs (full_career). */
    private List<Map<String, Object>> buildSelectedJobs(List<UUID> jobIds) {
        List<Map<String, Object>> payload = new ArrayList<>();
        if (jobIds == null) return payload;
        for (UUID id : jobIds) {
            payload.add(jobMap(jobs.findById(id).orElseThrow()));
        }
        return payload;
    }

    /**
     * Build the single target JD for RESUME_OPTIMIZATION: an existing job (mode select_job),
     * the pasted text (mode upload_jd), or a synthesized spec from the optimization mode
     * (modes 1–6). Always returns exactly one entry so the ATS stage has a target.
     */
    private List<Map<String, Object>> buildOptimizationJobs(UUID orgId, StartWorkflowRequest req) {
        if (req.jobId() != null) {
            Job j = jobs.findById(req.jobId()).orElseThrow();
            if (j.getOrgId() != null && !j.getOrgId().equals(orgId)) throw new SecurityException("forbidden");
            return List.of(jobMap(j));
        }
        String title;
        String description;
        if (req.jobDescriptionText() != null && !req.jobDescriptionText().isBlank()) {
            title = req.targetRole() == null || req.targetRole().isBlank() ? "Target Role" : req.targetRole();
            description = req.jobDescriptionText();
        } else {
            OptimizationModeCatalog.ModeSpec spec = OptimizationModeCatalog.specFor(req.optimizationMode());
            title = spec.title();
            description = spec.description();
        }
        Map<String, Object> jd = new HashMap<>();
        jd.put("id", "optimization-target");
        jd.put("title", title);
        jd.put("company", "Target Role");
        jd.put("description", description);
        jd.put("location", "");
        jd.put("salary", "");
        return List.of(jd);
    }

    private Map<String, Object> jobMap(Job j) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", j.getId().toString());
        m.put("title", j.getTitle());
        m.put("company", j.getCompany());
        m.put("description", j.getDescription());
        m.put("location", j.getLocation() == null ? "" : j.getLocation());
        m.put("salary", j.getSalaryRange() == null ? "" : j.getSalaryRange());
        return m;
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
        WorkflowRun merged = mergeResponse(run, resp, actor, decision, feedback);
        maybeCreateResumeVersion(run, merged, resp);
        return merged;
    }

    /**
     * After a RESUME_OPTIMIZATION run completes (approved → resume_export ran), persist the
     * optimized resume as a new version + DOCX. Skipped for full_career runs, rejected runs
     * (no resume_export output), and any run whose state lacks resume_id. Never throws — a
     * version-creation failure must not corrupt the workflow response.
     */
    private void maybeCreateResumeVersion(WorkflowRun oldRun, WorkflowRun merged, AgentRunResponse resp) {
        try {
            Map<String, Object> state = resp.state() == null ? Map.of() : resp.state();
            String wt = asString(state.get("workflow_type"));
            if (!WORKFLOW_TYPE_RESUME_OPT.equalsIgnoreCase(wt == null ? "" : wt)) return;
            if (!"COMPLETED".equals(deriveDisplayStatus(merged))) return;
            if (!(state.get("optimized_resume") instanceof Map<?, ?> opt) || opt.isEmpty()) return;

            UUID resumeId = resumeIdOf(oldRun);
            if (resumeId == null) {
                log.warn("Cannot create resume version: no resume_id in state for thread={}", merged.getThreadId());
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> optimized = (Map<String, Object>) opt;
            resumeVersions.createVersion(new ResumeVersionService.NewVersion(
                    merged.getUserId(), merged.getOrgId(), resumeId, merged.getThreadId(),
                    asString(state.get("optimization_mode")),
                    intOrNull(state, "ats_before"), intOrNull(state, "ats_after"),
                    providerForStage(state, "resume_export"),
                    optimized));
            events.publishResumeEvent(merged.getThreadId(), "resume.optimization.completed", new HashMap<>(Map.of(
                    "threadId", merged.getThreadId(),
                    "resumeId", resumeId.toString(),
                    "userId", merged.getUserId().toString())));
        } catch (Exception e) {
            log.error("Resume version creation failed for thread={}: {}", merged.getThreadId(), e.toString(), e);
        }
    }

    /** Parse the persisted state blob for the originating resume_id. */
    private UUID resumeIdOf(WorkflowRun run) {
        Object v = parseState(run).get("resume_id");
        if (v == null) return null;
        try {
            return UUID.fromString(v.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The provider that served a given stage, from the agent_execution telemetry. */
    private String providerForStage(Map<String, Object> state, String stageKey) {
        Map<String, Object> entry = indexExecutionByStage(state).get(stageKey);
        return entry == null ? null : asString(entry.get("provider"));
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
        // Stamp resume_id into the state blob so resume-time version creation can find its
        // resume (workflow_runs has no resume_id column). The agent echoes workflow_type /
        // optimization_mode itself; resume_id is ours to carry.
        Map<String, Object> state = new HashMap<>(resp.state() == null ? Map.of() : resp.state());
        state.put("resume_id", req.resumeId().toString());

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
        // Carry resume_id forward — the agent's fresh state doesn't echo it, but downstream
        // reads (version creation, UI) need it to persist across the resume.
        if (!state.containsKey("resume_id")) {
            UUID resumeId = resumeIdOf(run);
            if (resumeId != null) state.put("resume_id", resumeId.toString());
        }
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

    // The shorter RESUME_OPTIMIZATION path. Only these stages ever run on that template,
    // so the timeline must use this list — otherwise job_discovery/interview_prep/etc. would
    // be perpetually PENDING and the run could never derive COMPLETED/INTERRUPTED.
    private static final String[][] RESUME_OPTIMIZATION_STAGES = {
        {"resume_intelligence", "Resume Intelligence"},
        {"ats_optimization", "ATS Optimization"},
        {"human_approval", "Human Approval"},
        {"resume_export", "Resume Export"}
    };

    /** Pick the stage list for a run from its persisted workflow_type. */
    private static String[][] stagesFor(Map<String, Object> state) {
        Object wt = state.get("workflow_type");
        if (wt instanceof String s && WORKFLOW_TYPE_RESUME_OPT.equalsIgnoreCase(s.trim())) {
            return RESUME_OPTIMIZATION_STAGES;
        }
        return PIPELINE_STAGES;
    }

    /** Post-approval terminal stages (run only after an approval, not before the gate). */
    private static boolean isPostGateStage(String stageKey) {
        return "application_tracking".equals(stageKey) || "resume_export".equals(stageKey);
    }

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

        for (String[] stage : stagesFor(state)) {
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

            if (isPostGateStage(stageKey)) {                   // post-gate stage
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
            case "resume_export" -> nonEmpty(state.get("optimized_resume"));
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
