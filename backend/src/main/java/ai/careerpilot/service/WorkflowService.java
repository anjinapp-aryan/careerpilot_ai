package ai.careerpilot.service;

import ai.careerpilot.agent.AgentServiceClient;
import ai.careerpilot.api.dto.AgentServiceDtos.AgentRunResponse;
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
    public WorkflowRun resume(UUID userId, String threadId, String decision, String feedback) {
        WorkflowRun run = runs.findByThreadId(threadId).orElseThrow();
        if (!run.getUserId().equals(userId)) throw new SecurityException("forbidden");
        AgentRunResponse resp = agent.resumeRun(threadId, decision, feedback);
        return mergeResponse(run, resp);
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

    private WorkflowRun mergeResponse(WorkflowRun run, AgentRunResponse resp) {
        return mergeState(run, mapStatus(resp.status()), resp.state());
    }

    private WorkflowRun mergeState(WorkflowRun run, String status, Map<String, Object> state) {
        run.setStatus(status);
        run.setResumeScore(intOrNull(state, "resume_score"));
        run.setJobMatchScore(intOrNull(state, "job_match_score"));
        run.setAtsScore(intOrNull(state, "ats_score"));
        run.setInterviewReadinessScore(intOrNull(state, "interview_readiness_score"));
        try {
            run.setState(mapper.writeValueAsString(state));
            log.debug("Workflow State Persisted: thread={}, status={}", run.getThreadId(), status);
        } catch (Exception e) {
            log.error("Failed to serialize workflow state: {}", e.getMessage());
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
            case "error" -> "ERROR";
            default -> "RUNNING";
        };
    }

    // ---- Response mapping ----

    public WorkflowRunResponse toResponse(WorkflowRun run) {
        Map<String, Object> stateMap = new HashMap<>();
        try {
            if (run.getState() != null && !run.getState().isEmpty()) {
                stateMap = mapper.readValue(run.getState(), Map.class);
            }
        } catch (Exception e) {
            log.warn("Failed to parse workflow state for {}: {}", run.getThreadId(), e.getMessage());
        }
        return new WorkflowRunResponse(
                run.getId(),
                run.getThreadId(),
                run.getStatus(),
                run.getTargetRole(),
                run.getTargetSeniority(),
                run.getResumeScore(),
                run.getJobMatchScore(),
                run.getAtsScore(),
                run.getInterviewReadinessScore(),
                stateMap,
                run.getErrorMessage(),
                run.getCreatedAt(),
                run.getUpdatedAt());
    }

    public List<WorkflowRunResponse> toResponseList(List<WorkflowRun> runs) {
        return runs.stream().map(this::toResponse).toList();
    }
}
