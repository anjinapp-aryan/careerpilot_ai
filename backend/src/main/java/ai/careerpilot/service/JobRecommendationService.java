package ai.careerpilot.service;

import ai.careerpilot.api.dto.JobRecommendationDtos.CandidateProfileSummary;
import ai.careerpilot.api.dto.JobRecommendationDtos.RecommendedJob;
import ai.careerpilot.api.dto.JobRecommendationDtos.RecommendedJobsResponse;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.WorkflowRun;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Stage 1 "Recommended Jobs": deterministic, AI-free scoring of the org's existing job
 * pool against the candidate snapshot already produced by the LangGraph workflow
 * (resume_intelligence + job_discovery outputs persisted in WorkflowRun.state). Read-only
 * across existing tables — no new providers, no schema changes, no workflow execution.
 */
@Service
public class JobRecommendationService {

    /**
     * Bounded vocabulary used only to detect skill mentions inside free-text job
     * descriptions (so we can report "missing" skills). Not persisted, not AI-derived —
     * mirrors the lowercase canonical style the resume_intelligence agent already emits.
     */
    private static final List<String> SKILL_VOCABULARY = List.of(
            "java", "spring boot", "spring", "kotlin", "python", "javascript", "typescript",
            "react", "node", "node.js", "go", "golang", "rust", "c++", "c#", ".net",
            "kubernetes", "docker", "aws", "azure", "gcp", "terraform", "ansible",
            "kafka", "rabbitmq", "redis", "postgresql", "mysql", "mongodb", "elasticsearch",
            "graphql", "rest", "microservices", "ci/cd", "jenkins", "git", "linux",
            "machine learning", "data engineering", "sql", "nosql", "fastapi", "django",
            "flask", "spring cloud", "hibernate", "jpa", "vue", "angular", "next.js",
            "ci", "cd", "agile", "scrum", "leadership", "mentoring", "system design");

    private final WorkflowRunRepository runs;
    private final JobRepository jobs;
    private final ObjectMapper mapper = new ObjectMapper();

    public JobRecommendationService(WorkflowRunRepository runs, JobRepository jobs) {
        this.runs = runs;
        this.jobs = jobs;
    }

    public RecommendedJobsResponse recommend(UUID userId, UUID orgId, int limit) {
        WorkflowRun latest = runs.findTop20ByUserIdOrderByCreatedAtDesc(userId).stream()
                .findFirst().orElse(null);
        if (latest == null) {
            return new RecommendedJobsResponse(null, List.of());
        }

        Map<String, Object> state = parseState(latest);
        List<String> extractedSkills = stringList(state.get("extracted_skills"));
        List<String> targetLocations = stringList(state.get("target_locations"));
        Map<String, Object> candidateProfile = asMap(state.get("candidate_profile"));

        CandidateProfileSummary profile = new CandidateProfileSummary(
                intOrNull(candidateProfile.get("years_experience")),
                stringOrNull(candidateProfile.get("current_title")),
                extractedSkills,
                preferredRoles(latest),
                latest.getResumeScore());

        List<Job> pool = jobs.search(orgId, null, PageRequest.of(0, 50)).getContent();

        List<RecommendedJob> ranked = pool.stream()
                .map(job -> score(job, extractedSkills, latest.getTargetRole(), targetLocations))
                .sorted(Comparator.comparingInt(RecommendedJob::matchScore).reversed())
                .limit(Math.max(1, limit))
                .toList();

        return new RecommendedJobsResponse(profile, ranked);
    }

    private RecommendedJob score(Job job, List<String> candidateSkills, String targetRole, List<String> targetLocations) {
        String haystack = ((job.getTitle() == null ? "" : job.getTitle()) + " "
                + (job.getDescription() == null ? "" : job.getDescription())).toLowerCase();

        Set<String> candidateSkillSet = candidateSkills.stream().map(String::toLowerCase).collect(Collectors.toSet());

        List<String> mentionedSkills = SKILL_VOCABULARY.stream()
                .filter(haystack::contains)
                .toList();
        List<String> matchedSkills = mentionedSkills.stream().filter(candidateSkillSet::contains).toList();
        List<String> missingSkills = mentionedSkills.stream().filter(s -> !candidateSkillSet.contains(s)).toList();

        int skillsScore = mentionedSkills.isEmpty() ? 50 : (matchedSkills.size() * 100 / mentionedSkills.size());

        int roleScore;
        if (targetRole == null || targetRole.isBlank()) {
            roleScore = 50;
        } else {
            String role = targetRole.toLowerCase();
            roleScore = haystack.contains(role) ? 100
                    : Arrays.stream(role.split("\\s+")).anyMatch(haystack::contains) ? 60
                    : 20;
        }

        int locationScore;
        String jobLocation = job.getLocation() == null ? "" : job.getLocation().toLowerCase();
        if (haystack.contains("remote") || jobLocation.contains("remote")) {
            locationScore = 100;
        } else if (targetLocations.isEmpty()) {
            locationScore = 50;
        } else {
            locationScore = targetLocations.stream().anyMatch(loc -> jobLocation.contains(loc.toLowerCase())) ? 100 : 30;
        }

        int total = Math.round(skillsScore * 0.6f + roleScore * 0.25f + locationScore * 0.15f);
        return new RecommendedJob(job, Math.min(100, Math.max(0, total)), matchedSkills, missingSkills);
    }

    private List<String> preferredRoles(WorkflowRun run) {
        List<String> roles = new ArrayList<>();
        if (run.getTargetRole() != null && !run.getTargetRole().isBlank()) roles.add(run.getTargetRole());
        if (run.getTargetSeniority() != null && !run.getTargetSeniority().isBlank()) roles.add(run.getTargetSeniority());
        return roles;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseState(WorkflowRun run) {
        try {
            return mapper.readValue(run.getState(), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private Integer intOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
