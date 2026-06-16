package ai.careerpilot.service;

import ai.careerpilot.agent.AgentServiceClient;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.domain.WorkflowRun;
import ai.careerpilot.kafka.WorkflowEventProducer;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.repo.WorkflowRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkflowService {

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

        JsonNode resp = agent.startRun(body);
        return persistFromResponse(userId, orgId, req, resp);
    }

    @Transactional
    public WorkflowRun resume(UUID userId, String threadId, String decision, String feedback) {
        WorkflowRun run = runs.findByThreadId(threadId).orElseThrow();
        if (!run.getUserId().equals(userId)) throw new SecurityException("forbidden");
        JsonNode resp = agent.resumeRun(threadId, decision, feedback);
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

    private WorkflowRun persistFromResponse(UUID userId, UUID orgId, StartWorkflowRequest req, JsonNode resp) {
        String threadId = resp.path("thread_id").asText();
        String status = mapStatus(resp.path("status").asText());
        JsonNode state = resp.path("state");

        WorkflowRun run = runs.findByThreadId(threadId).orElseGet(() -> WorkflowRun.builder()
                .userId(userId).orgId(orgId).threadId(threadId)
                .targetRole(req.targetRole()).targetSeniority(req.targetSeniority())
                .status(status).state("{}").build());

        return mergeState(run, status, state);
    }

    private WorkflowRun mergeResponse(WorkflowRun run, JsonNode resp) {
        return mergeState(run, mapStatus(resp.path("status").asText()), resp.path("state"));
    }

    private WorkflowRun mergeState(WorkflowRun run, String status, JsonNode state) {
        run.setStatus(status);
        run.setResumeScore(intOrNull(state, "resume_score"));
        run.setJobMatchScore(intOrNull(state, "job_match_score"));
        run.setAtsScore(intOrNull(state, "ats_score"));
        run.setInterviewReadinessScore(intOrNull(state, "interview_readiness_score"));
        try {
            run.setState(mapper.writeValueAsString(state));
        } catch (Exception e) {
            run.setState("{}");
        }
        WorkflowRun saved = runs.save(run);
        events.publish(saved.getThreadId(),
                Map.of("threadId", saved.getThreadId(), "status", saved.getStatus(), "userId", saved.getUserId().toString()));
        return saved;
    }

    private Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isInt() ? v.asInt() : null;
    }

    private String mapStatus(String s) {
        return switch (s) {
            case "interrupted" -> "INTERRUPTED";
            case "completed" -> "COMPLETED";
            case "error" -> "ERROR";
            default -> "RUNNING";
        };
    }
}
