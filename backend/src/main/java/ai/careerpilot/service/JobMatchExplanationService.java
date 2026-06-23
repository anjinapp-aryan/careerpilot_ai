package ai.careerpilot.service;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.api.dto.JobMatchExplanationDto;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.domain.JobRecommendationExplanation;
import ai.careerpilot.domain.WorkflowRun;
import ai.careerpilot.repo.JobRecommendationExplanationRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.WorkflowRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * "Why am I a match?" explainability. The single LLM-backed seam in the job engine:
 * rule-based scoring stays deterministic and cheap; this calls {@link AiGatewayService}
 * once per (user, job) and caches the result, so repeat clicks never re-hit the LLM.
 */
@Service
public class JobMatchExplanationService {

    private static final Logger log = LoggerFactory.getLogger(JobMatchExplanationService.class);
    private static final int MAX_JD = 4000;

    private static final String SYSTEM = """
            You are a career coach assistant. Given a candidate profile and a job, explain the fit.
            Respond with STRICT JSON only (no markdown, no prose) using exactly these keys:
            {"matchingSkills":[],"missingSkills":[],"resumeImprovements":[],"atsImprovements":[]}
            Each array holds short strings. resumeImprovements = concrete edits to the resume to win
            this role. atsImprovements = keyword/formatting changes to pass ATS screening for it.""";

    private final AiGatewayService gateway;
    private final JobRepository jobs;
    private final WorkflowRunRepository runs;
    private final JobRecommendationRepository recommendations;
    private final JobRecommendationExplanationRepository cache;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean enabled;

    public JobMatchExplanationService(AiGatewayService gateway,
                                      JobRepository jobs,
                                      WorkflowRunRepository runs,
                                      JobRecommendationRepository recommendations,
                                      JobRecommendationExplanationRepository cache,
                                      @Value("${jobs.explain.enabled:true}") boolean enabled) {
        this.gateway = gateway;
        this.jobs = jobs;
        this.runs = runs;
        this.recommendations = recommendations;
        this.cache = cache;
        this.enabled = enabled;
    }

    @Transactional
    public JobMatchExplanationDto explain(UUID userId, UUID orgId, UUID jobId) {
        if (!enabled) {
            throw new IllegalStateException("Job match explanations are disabled");
        }
        Job job = jobs.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found"));
        // Multi-tenant guard: global discovered jobs (org_id NULL) are visible to all;
        // org jobs only to their own org.
        if (job.getOrgId() != null && !job.getOrgId().equals(orgId)) {
            throw new IllegalArgumentException("Job not found or access denied");
        }

        var cached = cache.findByUserIdAndJobId(userId, jobId);
        if (cached.isPresent()) {
            return toDto(cached.get());
        }

        // Candidate signals: prefer the persisted recommendation, fall back to the workflow run.
        JobRecommendation rec = recommendations.findByUserIdAndJobId(userId, jobId).orElse(null);
        WorkflowRun latest = runs.findTop20ByUserIdOrderByCreatedAtDesc(userId).stream()
                .findFirst().orElse(null);
        List<String> candidateSkills = latest == null ? List.of()
                : stringList(parseState(latest).get("extracted_skills"));
        Integer atsScore = latest == null ? null : latest.getAtsScore();

        String prompt = buildPrompt(job, candidateSkills, rec, atsScore);

        JobMatchExplanationDto dto;
        String model;
        try {
            String raw = gateway.chat(List.of(ChatMessage.user(prompt)), SYSTEM);
            model = gateway.getLastUsedProvider();
            dto = parse(raw, rec, model);
        } catch (Exception e) {
            log.warn("Explain failed for user={} job={}: {} — serving rule-based explanation",
                    userId, jobId, e.toString());
            // Graceful degradation: build a useful deterministic explanation from the matcher
            // output. Not cached, so a later (working) LLM call still replaces it.
            return buildRuleBased(job, rec, candidateSkills);
        }

        cache.save(JobRecommendationExplanation.builder()
                .userId(userId).jobId(jobId)
                .matchingSkills(join(dto.matchingSkills()))
                .missingSkills(join(dto.missingSkills()))
                .resumeImprovements(join(dto.resumeImprovements()))
                .atsImprovements(join(dto.atsImprovements()))
                .modelUsed(dto.modelUsed())
                .build());
        return dto;
    }

    /** Deterministic explanation when the LLM chain is unavailable — still actionable. */
    private JobMatchExplanationDto buildRuleBased(Job job, JobRecommendation rec, List<String> candidateSkills) {
        List<String> matching = rec != null ? csv(rec.getMatchingSkills())
                : new ArrayList<>(candidateSkills);
        List<String> missing = rec != null ? csv(rec.getMissingSkills()) : List.of();

        List<String> resume = new ArrayList<>();
        if (!matching.isEmpty()) {
            resume.add("Lead with your " + String.join(", ", matching.subList(0, Math.min(3, matching.size())))
                    + " experience — quantify the impact (scale, performance, %).");
        }
        for (String skill : missing.subList(0, Math.min(3, missing.size()))) {
            resume.add("Surface any " + skill + " exposure (projects, certifications, side work) to close the gap.");
        }
        resume.add("Mirror the exact role title (\"" + job.getTitle() + "\") and seniority in your summary.");

        List<String> ats = new ArrayList<>();
        if (!missing.isEmpty()) {
            ats.add("Add these missing keywords verbatim where truthful: "
                    + String.join(", ", missing.subList(0, Math.min(5, missing.size()))) + ".");
        }
        ats.add("Use a single-column layout and standard section headers so parsers read it cleanly.");
        ats.add("Include both the acronym and expansion for key tools (e.g. \"CI/CD (Jenkins)\").");

        return new JobMatchExplanationDto(matching, missing, resume, ats, "rule-based");
    }

    private String buildPrompt(Job job, List<String> candidateSkills, JobRecommendation rec, Integer atsScore) {
        StringBuilder sb = new StringBuilder();
        sb.append("CANDIDATE_SKILLS: ").append(String.join(", ", candidateSkills)).append('\n');
        if (atsScore != null) sb.append("CANDIDATE_ATS_SCORE: ").append(atsScore).append("/100\n");
        if (rec != null) {
            sb.append("RULE_BASED_MATCH_SCORE: ").append(rec.getMatchScore()).append("/100\n");
            if (rec.getMatchingSkills() != null) sb.append("PRECOMPUTED_MATCHING: ").append(rec.getMatchingSkills()).append('\n');
            if (rec.getMissingSkills() != null) sb.append("PRECOMPUTED_MISSING: ").append(rec.getMissingSkills()).append('\n');
        }
        sb.append("\nJOB_TITLE: ").append(job.getTitle()).append('\n');
        sb.append("JOB_COMPANY: ").append(job.getCompany()).append('\n');
        if (job.getLocation() != null) sb.append("JOB_LOCATION: ").append(job.getLocation()).append('\n');
        sb.append("JOB_DESCRIPTION:\n").append(truncate(job.getDescription(), MAX_JD));
        return sb.toString();
    }

    private JobMatchExplanationDto parse(String raw, JobRecommendation rec, String model) throws Exception {
        String json = extractJson(raw);
        JsonNode n = mapper.readTree(json);
        List<String> matching = arr(n.get("matchingSkills"));
        List<String> missing = arr(n.get("missingSkills"));
        // Backfill from the deterministic matcher when the model omits them.
        if (matching.isEmpty() && rec != null) matching = csv(rec.getMatchingSkills());
        if (missing.isEmpty() && rec != null) missing = csv(rec.getMissingSkills());
        return new JobMatchExplanationDto(matching, missing,
                arr(n.get("resumeImprovements")), arr(n.get("atsImprovements")), model);
    }

    /** Tolerate ```json fences / leading prose by slicing to the outermost JSON object. */
    private static String extractJson(String raw) {
        if (raw == null) return "{}";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : "{}";
    }

    private List<String> arr(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        node.forEach(e -> {
            String s = e.asText("").trim();
            if (!s.isEmpty()) out.add(s);
        });
        return out;
    }

    private JobMatchExplanationDto toDto(JobRecommendationExplanation e) {
        return new JobMatchExplanationDto(csv(e.getMatchingSkills()), csv(e.getMissingSkills()),
                csv(e.getResumeImprovements()), csv(e.getAtsImprovements()), e.getModelUsed());
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

    private static List<String> csv(String v) {
        if (v == null || v.isBlank()) return List.of();
        return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String join(List<String> xs) {
        return (xs == null || xs.isEmpty()) ? null : String.join(",", xs);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
