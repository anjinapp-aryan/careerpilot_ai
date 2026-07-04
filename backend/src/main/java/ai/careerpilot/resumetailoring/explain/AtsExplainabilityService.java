package ai.careerpilot.resumetailoring.explain;

import ai.careerpilot.domain.*;
import ai.careerpilot.repo.*;
import ai.careerpilot.resumetailoring.event.AtsExplainabilityCompletedEvent;
import ai.careerpilot.resumetailoring.gap.GapAnalysisService;
import ai.careerpilot.service.profile.JsonLists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Phase 2D.4 — explains WHY an ATS score came out the way it did. Deterministic, arithmetic-only:
 * matched items are keywords the job names that the tailored resume text actually contains;
 * missing items come straight from the persisted {@code ResumeGapAnalysis}; confidence is a fixed
 * function of how much lineage data was available. <b>No LLM scoring</b> — the narrative
 * recommendations are deterministic templates ("Add X experience examples."), which also keeps
 * this stage repeatable and free.
 *
 * <p>On success, publishes {@link AtsExplainabilityCompletedEvent} for the next stage (2D.5).
 * Never throws; flag-gated dark by {@code ats.explainability.enabled}.
 */
@Service
public class AtsExplainabilityService {

    private static final Logger log = LoggerFactory.getLogger(AtsExplainabilityService.class);

    private final ResumeTailoringRepository tailorings;
    private final ResumeAtsAnalysisRepository atsAnalyses;
    private final ResumeGapAnalysisRepository gapAnalyses;
    private final JobRepository jobs;
    private final JobAiEnrichmentRepository enrichment;
    private final CandidateProfileRepository profiles;
    private final ResumeAtsExplanationRepository explanations;
    private final AtsExplainabilityMetrics metrics;
    private final ApplicationEventPublisher events;
    private final boolean enabled;

    public AtsExplainabilityService(ResumeTailoringRepository tailorings,
                                    ResumeAtsAnalysisRepository atsAnalyses,
                                    ResumeGapAnalysisRepository gapAnalyses,
                                    JobRepository jobs, JobAiEnrichmentRepository enrichment,
                                    CandidateProfileRepository profiles,
                                    ResumeAtsExplanationRepository explanations,
                                    AtsExplainabilityMetrics metrics,
                                    ApplicationEventPublisher events,
                                    @Value("${ats.explainability.enabled:false}") boolean enabled) {
        this.tailorings = tailorings;
        this.atsAnalyses = atsAnalyses;
        this.gapAnalyses = gapAnalyses;
        this.jobs = jobs;
        this.enrichment = enrichment;
        this.profiles = profiles;
        this.explanations = explanations;
        this.metrics = metrics;
        this.events = events;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Transactional
    public Optional<ResumeAtsExplanation> explain(UUID userId, UUID jobId, UUID resumeTailoringId,
                                                  UUID atsAnalysisId, UUID gapAnalysisId) {
        if (!enabled) return Optional.empty();
        long start = System.currentTimeMillis();
        metrics.recordRequest();
        try {
            ResumeTailoring tailoring = resumeTailoringId != null
                    ? tailorings.findById(resumeTailoringId).orElse(null)
                    : tailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId).orElse(null);
            Job job = jobs.findById(jobId).orElse(null);
            if (tailoring == null || job == null) {
                log.warn("ATS_EXPLAIN missing tailoring or job user={} job={}", userId, jobId);
                metrics.recordFailure();
                return Optional.empty();
            }

            ResumeAtsAnalysis ats = atsAnalysisId != null ? atsAnalyses.findById(atsAnalysisId).orElse(null) : null;
            ResumeGapAnalysis gap = gapAnalysisId != null ? gapAnalyses.findById(gapAnalysisId).orElse(null) : null;
            JobAiEnrichment jobEnrichment = enrichment.findByJobId(jobId).orElse(null);
            CandidateProfile profile = profiles.findByUserId(userId).orElse(null);

            String resumeText = lower(tailoring.getTailoredResumeText());
            String jobText = lower(job.getDescription()) + " " + lower(job.getSkills());

            List<String> requiredSkills = jobEnrichment != null
                    && !JsonLists.toList(jobEnrichment.getNormalizedSkillsJson()).isEmpty()
                    ? JsonLists.toList(jobEnrichment.getNormalizedSkillsJson())
                    : csv(job.getSkills());
            List<String> matchedSkills = requiredSkills.stream()
                    .filter(s -> resumeText.contains(lower(s))).distinct().toList();

            String matchedExperience = matchedExperience(profile, job);
            List<String> matchedCloud = matchedKeywords(GapAnalysisService.CLOUD_KEYWORDS, jobText, resumeText);
            List<String> matchedLeadership = matchedKeywords(GapAnalysisService.LEADERSHIP_KEYWORDS, jobText, resumeText);
            List<String> matchedArchitecture = matchedKeywords(GapAnalysisService.ARCHITECTURE_KEYWORDS, jobText, resumeText);

            List<String> missingItems = gap != null ? collectMissing(gap) : List.of();
            List<String> recommendations = missingItems.stream().limit(5)
                    .map(item -> "Add " + item + " experience examples.").toList();

            // Confidence = fixed function of lineage completeness (deterministic, explainable).
            double confidence = 0.5
                    + (ats != null ? 0.2 : 0.0)
                    + (gap != null ? 0.2 : 0.0)
                    + (jobEnrichment != null ? 0.05 : 0.0);

            ResumeAtsExplanation saved = explanations.save(ResumeAtsExplanation.builder()
                    .userId(userId).jobId(jobId)
                    .resumeTailoringId(tailoring.getId())
                    .resumeAtsAnalysisId(ats != null ? ats.getId() : null)
                    .gapAnalysisId(gap != null ? gap.getId() : null)
                    .atsScore(ats != null ? ats.getAtsScore() : null)
                    .matchedSkills(join(matchedSkills))
                    .matchedExperience(matchedExperience)
                    .matchedCloud(join(matchedCloud))
                    .matchedLeadership(join(matchedLeadership))
                    .matchedArchitecture(join(matchedArchitecture))
                    .missingItems(join(missingItems))
                    .confidence(BigDecimal.valueOf(confidence).setScale(2, RoundingMode.HALF_UP))
                    .recommendations(join(recommendations))
                    .build());

            metrics.recordSuccess();
            log.info("ATS_EXPLAIN generated user={} job={} matched={} missing={}", userId, jobId,
                    matchedSkills.size(), missingItems.size());
            events.publishEvent(new AtsExplainabilityCompletedEvent(userId, jobId, tailoring.getId(),
                    atsAnalysisId, gapAnalysisId, saved.getId()));
            return Optional.of(saved);
        } catch (Exception e) {
            metrics.recordFailure();
            log.warn("ATS_EXPLAIN error user={} job={}: {}", userId, jobId, e.toString());
            return Optional.empty();
        } finally {
            metrics.recordLatency(System.currentTimeMillis() - start);
        }
    }

    public Optional<ResumeAtsExplanation> latest(UUID userId, UUID jobId) {
        return explanations.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId);
    }

    private static String matchedExperience(CandidateProfile profile, Job job) {
        Integer have = profile != null ? profile.getYearsExperience() : null;
        Integer need = job.getRequiredExperience();
        if (have == null) return null;
        if (need == null) return have + " years experience";
        return have >= need
                ? have + " years experience (meets required " + need + ")"
                : null; // not matched — surfaces via gap analysis instead
    }

    private static List<String> matchedKeywords(List<String> keywords, String jobText, String resumeText) {
        return keywords.stream().filter(jobText::contains).filter(resumeText::contains).toList();
    }

    private static List<String> collectMissing(ResumeGapAnalysis gap) {
        List<String> out = new ArrayList<>();
        for (String csv : new String[]{gap.getMissingSkills(), gap.getMissingCertifications(),
                gap.getMissingCloud(), gap.getMissingLeadership(), gap.getMissingArchitecture(),
                gap.getMissingDomains()}) {
            out.addAll(csv(csv));
        }
        return out.stream().distinct().toList();
    }

    private static List<String> csv(String v) {
        if (v == null || v.isBlank()) return List.of();
        return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String join(List<String> xs) {
        return xs == null || xs.isEmpty() ? null : String.join(",", xs);
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
