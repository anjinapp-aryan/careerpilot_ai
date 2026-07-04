package ai.careerpilot.resumetailoring.ats;

import ai.careerpilot.ai.AiGatewayProperties;
import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.ResumeAtsAnalysis;
import ai.careerpilot.domain.ResumeTailoring;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.ResumeAtsAnalysisRepository;
import ai.careerpilot.repo.ResumeTailoringRepository;
import ai.careerpilot.resumetailoring.scoring.ResumeImprovementCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2D.2 — analyzes the <b>latest tailored resume</b> (from {@link ResumeTailoring}, Phase
 * 2D.1) against its job: a deterministic keyword-overlap ATS score (reusing {@link
 * ResumeImprovementCalculator} unchanged) plus an LLM-generated breakdown of matched/missing
 * keywords and concrete optimization suggestions. Deliberately scoped to the tailored resume, not
 * the original — {@code JobMatchExplanationService} already answers "why is this a match" for the
 * original resume and is untouched by this feature.
 *
 * <p>Immutable-append like {@code ResumeTailoring} itself: never mutates a prior analysis, always
 * inserts a new {@link ResumeAtsAnalysis} row. No cache and no separate audit table (narrower scope
 * than 2D.1 — a sub-optimal suggestion is low-stakes compared to a fabricated resume claim);
 * failures are recorded on the caller's job row ({@code AtsOptimizationJobService}), not here.
 * Never throws — flag-gated via {@link #isEnabled()}, and any exception is caught and turned into
 * an empty result.
 */
@Service
public class AtsOptimizationService {

    private static final Logger log = LoggerFactory.getLogger(AtsOptimizationService.class);
    private static final int MAX_JD = 4000;
    private static final int MAX_RESUME = 8000;

    private static final String SYSTEM_PROMPT = """
            You are an ATS (Applicant Tracking System) optimization expert. Given a candidate's
            already-tailored resume and the job it was tailored for, analyze how well this specific
            resume will score in an ATS keyword scan and how to improve it further.

            Respond with STRICT JSON only (no markdown, no prose) using exactly these keys:
            {"matchedKeywords":[],"missingKeywords":[],"suggestions":[]}

            matchedKeywords = job-relevant keywords/skills already present in the resume text.
            missingKeywords = job-relevant keywords/skills the job description names that are absent
            from the resume text. suggestions = concrete, specific edits to improve ATS keyword
            coverage and readability for THIS resume and THIS job. Do not suggest fabricating
            experience, skills, or credentials not already true of the candidate — only surfacing,
            rewording, or reorganizing existing content.""";

    private final ResumeTailoringRepository tailorings;
    private final JobRepository jobs;
    private final ResumeAtsAnalysisRepository analyses;
    private final ResumeImprovementCalculator improvementCalculator;
    private final AiGatewayService ai;
    private final AiGatewayProperties aiProps;
    private final AtsOptimizationMetrics metrics;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean enabled;

    public AtsOptimizationService(ResumeTailoringRepository tailorings, JobRepository jobs,
                                  ResumeAtsAnalysisRepository analyses,
                                  ResumeImprovementCalculator improvementCalculator,
                                  AiGatewayService ai, AiGatewayProperties aiProps,
                                  AtsOptimizationMetrics metrics,
                                  @Value("${ats.optimization.enabled:false}") boolean enabled) {
        this.tailorings = tailorings;
        this.jobs = jobs;
        this.analyses = analyses;
        this.improvementCalculator = improvementCalculator;
        this.ai = ai;
        this.aiProps = aiProps;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Analyze the latest tailored resume for (userId, jobId). Empty when disabled, missing, or on any failure. */
    @Transactional
    public Optional<ResumeAtsAnalysis> analyze(UUID userId, UUID jobId) {
        if (!enabled) return Optional.empty();
        long start = System.currentTimeMillis();
        metrics.recordRequest();
        try {
            ResumeTailoring tailoring = tailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId)
                    .orElse(null);
            Job job = jobs.findById(jobId).orElse(null);
            if (tailoring == null || job == null) {
                log.warn("ATS_OPTIMIZATION missing tailoring or job user={} job={}", userId, jobId);
                metrics.recordFailure();
                return Optional.empty();
            }

            List<String> jobSkills = csvToList(job.getSkills());
            int atsScore = improvementCalculator.atsScore(tailoring.getTailoredResumeText(), jobSkills);

            List<String> preferred = aiProps.getRouting().getOrDefault("atsOptimization", List.of());
            String raw = ai.chat(List.of(ChatMessage.user(buildUserPrompt(tailoring, job))), SYSTEM_PROMPT, preferred);
            String model = ai.getLastUsedProvider();
            metrics.recordProviderUsed(model);

            AnalysisResult parsed = parse(raw);

            ResumeAtsAnalysis saved = analyses.save(ResumeAtsAnalysis.builder()
                    .userId(userId).jobId(jobId).resumeTailoringId(tailoring.getId())
                    .atsScore(atsScore)
                    .matchedKeywords(join(parsed.matchedKeywords()))
                    .missingKeywords(join(parsed.missingKeywords()))
                    .suggestions(join(parsed.suggestions()))
                    .modelUsed(model)
                    .status(ResumeAtsAnalysis.STATUS_GENERATED)
                    .build());
            log.info("ATS_OPTIMIZATION generated user={} job={} tailoringId={} atsScore={}",
                    userId, jobId, tailoring.getId(), atsScore);
            metrics.recordSuccess();
            return Optional.of(saved);
        } catch (Exception e) {
            log.warn("ATS_OPTIMIZATION error user={} job={}: {}", userId, jobId, e.toString());
            metrics.recordFailure();
            return Optional.empty();
        } finally {
            metrics.recordLatency(System.currentTimeMillis() - start);
        }
    }

    public Optional<ResumeAtsAnalysis> latest(UUID userId, UUID jobId) {
        return analyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId);
    }

    public List<ResumeAtsAnalysis> history(UUID userId, UUID jobId) {
        return analyses.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId);
    }

    private String buildUserPrompt(ResumeTailoring tailoring, Job job) {
        StringBuilder sb = new StringBuilder();
        sb.append("JOB_TITLE: ").append(job.getTitle()).append('\n');
        sb.append("JOB_COMPANY: ").append(job.getCompany()).append('\n');
        if (job.getSkills() != null) sb.append("JOB_SKILLS: ").append(job.getSkills()).append('\n');
        sb.append("JOB_DESCRIPTION:\n").append(truncate(job.getDescription(), MAX_JD)).append("\n\n");
        sb.append("TAILORED_RESUME:\n").append(truncate(tailoring.getTailoredResumeText(), MAX_RESUME));
        return sb.toString();
    }

    private record AnalysisResult(List<String> matchedKeywords, List<String> missingKeywords, List<String> suggestions) {
    }

    private AnalysisResult parse(String raw) throws Exception {
        String json = extractJson(raw);
        JsonNode n = mapper.readTree(json);
        return new AnalysisResult(arr(n.get("matchedKeywords")), arr(n.get("missingKeywords")), arr(n.get("suggestions")));
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

    private static List<String> csvToList(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String join(List<String> xs) {
        return (xs == null || xs.isEmpty()) ? null : String.join(",", xs);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
