package ai.careerpilot.resumetailoring.gap;

import ai.careerpilot.domain.*;
import ai.careerpilot.repo.*;
import ai.careerpilot.resumetailoring.event.GapAnalysisCompletedEvent;
import ai.careerpilot.service.profile.JsonLists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 2D.3 — deterministic, repeatable, <b>non-LLM</b> gap analysis: what does the job require
 * that the candidate (profile + original resume + tailored resume) does not evidence? Pure string/
 * keyword arithmetic over already-persisted data — same philosophy as {@code JobScoring} and
 * {@code ResumeImprovementCalculator}: identical inputs always produce identical output.
 *
 * <p>"Evidence" = case-insensitive containment in the union of tailored resume text, original
 * resume text, and the candidate profile's skills/technologies/certifications lists. Job
 * requirements come from the AI enrichment's normalized skills when present, else the job's own
 * skills CSV; cloud/leadership/architecture requirement signals are detected in the job
 * description via fixed keyword sets. {@code gapScore} = round(100 × missing / required), 0 when
 * the job names no requirements.
 *
 * <p>On success, publishes {@link GapAnalysisCompletedEvent} for the next pipeline stage (2D.4).
 * Never throws; flag-gated dark by {@code gap.analysis.enabled}.
 */
@Service
public class GapAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(GapAnalysisService.class);

    /** Shared with the 2D.4 explainability engine so "cloud/leadership/architecture" mean one thing. */
    public static final List<String> CLOUD_KEYWORDS = List.of(
            "aws", "azure", "gcp", "google cloud", "kubernetes", "docker", "terraform", "cloudformation",
            "lambda", "serverless", "eks", "ecs", "s3");
    public static final List<String> LEADERSHIP_KEYWORDS = List.of(
            "leadership", "team lead", "tech lead", "mentoring", "mentorship", "people management",
            "stakeholder management", "cross-functional");
    public static final List<String> ARCHITECTURE_KEYWORDS = List.of(
            "architecture", "system design", "microservices", "distributed systems", "event-driven",
            "scalability", "high availability", "domain-driven design");

    private final ResumeTailoringRepository tailorings;
    private final ResumeRepository resumes;
    private final JobRepository jobs;
    private final JobAiEnrichmentRepository enrichment;
    private final CandidateProfileRepository profiles;
    private final CandidateProfileVersionRepository profileVersions;
    private final CandidateBehaviorProfileRepository behaviorProfiles;
    private final ResumeGapAnalysisRepository gapAnalyses;
    private final GapAnalysisCache cache;
    private final GapAnalysisMetrics metrics;
    private final ApplicationEventPublisher events;
    private final boolean enabled;

    public GapAnalysisService(ResumeTailoringRepository tailorings, ResumeRepository resumes,
                              JobRepository jobs, JobAiEnrichmentRepository enrichment,
                              CandidateProfileRepository profiles,
                              CandidateProfileVersionRepository profileVersions,
                              CandidateBehaviorProfileRepository behaviorProfiles,
                              ResumeGapAnalysisRepository gapAnalyses,
                              GapAnalysisCache cache, GapAnalysisMetrics metrics,
                              ApplicationEventPublisher events,
                              @Value("${gap.analysis.enabled:false}") boolean enabled) {
        this.tailorings = tailorings;
        this.resumes = resumes;
        this.jobs = jobs;
        this.enrichment = enrichment;
        this.profiles = profiles;
        this.profileVersions = profileVersions;
        this.behaviorProfiles = behaviorProfiles;
        this.gapAnalyses = gapAnalyses;
        this.cache = cache;
        this.metrics = metrics;
        this.events = events;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Analyze gaps for the given tailored resume. Empty when disabled, inputs missing, or on any failure. */
    @Transactional
    public Optional<ResumeGapAnalysis> analyze(UUID userId, UUID jobId, UUID resumeTailoringId, UUID atsAnalysisId) {
        if (!enabled) return Optional.empty();
        long start = System.currentTimeMillis();
        metrics.recordRequest();
        try {
            ResumeTailoring tailoring = resumeTailoringId != null
                    ? tailorings.findById(resumeTailoringId).orElse(null)
                    : tailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId).orElse(null);
            Job job = jobs.findById(jobId).orElse(null);
            if (tailoring == null || job == null) {
                log.warn("GAP_ANALYSIS missing tailoring or job user={} job={}", userId, jobId);
                metrics.recordFailure();
                return Optional.empty();
            }

            Optional<UUID> cached = cache.get(userId, jobId, tailoring.getId(), atsAnalysisId);
            if (cached.isPresent()) {
                Optional<ResumeGapAnalysis> existing = gapAnalyses.findById(cached.get());
                if (existing.isPresent()) {
                    metrics.recordSuccess();
                    return existing;
                }
            }

            CandidateProfile profile = profiles.findByUserId(userId).orElse(null);
            JobAiEnrichment jobEnrichment = enrichment.findByJobId(jobId).orElse(null);
            String originalText = resumes.findByUserIdOrderByCreatedAtDesc(userId).stream()
                    .findFirst().map(Resume::getParsedText).orElse("");

            String evidence = buildEvidence(tailoring.getTailoredResumeText(), originalText, profile);

            List<String> requiredSkills = requiredSkills(job, jobEnrichment);
            String jobText = lower(job.getDescription()) + " " + lower(job.getSkills());

            List<String> missingSkills = missingFrom(requiredSkills, evidence);
            List<String> missingCerts = missingCertifications(jobText, profile, evidence);
            List<String> missingCloud = missingKeywords(CLOUD_KEYWORDS, jobText, evidence);
            List<String> missingLeadership = missingKeywords(LEADERSHIP_KEYWORDS, jobText, evidence);
            List<String> missingArchitecture = missingKeywords(ARCHITECTURE_KEYWORDS, jobText, evidence);
            List<String> missingDomains = missingDomains(jobEnrichment, profile, evidence);

            int required = requiredSkills.size()
                    + presentKeywords(CLOUD_KEYWORDS, jobText).size()
                    + presentKeywords(LEADERSHIP_KEYWORDS, jobText).size()
                    + presentKeywords(ARCHITECTURE_KEYWORDS, jobText).size()
                    + (jobEnrichment != null ? JsonLists.toList(jobEnrichment.getDomainsJson()).size() : 0);
            int missing = missingSkills.size() + missingCloud.size() + missingLeadership.size()
                    + missingArchitecture.size() + missingDomains.size();
            int gapScore = required == 0 ? 0 : (int) Math.round(missing * 100.0 / required);

            ResumeGapAnalysis saved = gapAnalyses.save(ResumeGapAnalysis.builder()
                    .userId(userId).jobId(jobId)
                    .resumeTailoringId(tailoring.getId())
                    .resumeAtsAnalysisId(atsAnalysisId)
                    .candidateProfileVersion(profileVersions.findByUserIdOrderByCreatedAtDesc(userId).stream()
                            .findFirst().map(CandidateProfileVersion::getId).orElse(null))
                    .behaviorProfileVersion(behaviorProfiles.findById(userId)
                            .map(CandidateBehaviorProfile::getUpdatedAt).orElse(null))
                    .missingSkills(join(missingSkills))
                    .missingCertifications(join(missingCerts))
                    .missingCloud(join(missingCloud))
                    .missingLeadership(join(missingLeadership))
                    .missingArchitecture(join(missingArchitecture))
                    .missingDomains(join(missingDomains))
                    .gapScore(Math.min(100, gapScore))
                    .build());

            cache.put(userId, jobId, tailoring.getId(), atsAnalysisId, saved.getId());
            metrics.recordSuccess();
            log.info("GAP_ANALYSIS generated user={} job={} gapScore={} missing={}", userId, jobId,
                    saved.getGapScore(), missing);
            events.publishEvent(new GapAnalysisCompletedEvent(userId, jobId, tailoring.getId(),
                    atsAnalysisId, saved.getId()));
            return Optional.of(saved);
        } catch (Exception e) {
            metrics.recordFailure();
            log.warn("GAP_ANALYSIS error user={} job={}: {}", userId, jobId, e.toString());
            return Optional.empty();
        } finally {
            metrics.recordLatency(System.currentTimeMillis() - start);
        }
    }

    public Optional<ResumeGapAnalysis> latest(UUID userId, UUID jobId) {
        return gapAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId);
    }

    // ── deterministic helpers ──

    private static String buildEvidence(String tailoredText, String originalText, CandidateProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append(lower(tailoredText)).append(' ').append(lower(originalText));
        if (profile != null) {
            for (String json : new String[]{profile.getSkillsJson(), profile.getTechnologiesJson(),
                    profile.getCertificationsJson(), profile.getDomainsJson()}) {
                sb.append(' ').append(JsonLists.toList(json).stream()
                        .map(GapAnalysisService::lower).collect(Collectors.joining(" ")));
            }
        }
        return sb.toString();
    }

    private static List<String> requiredSkills(Job job, JobAiEnrichment jobEnrichment) {
        List<String> normalized = jobEnrichment != null ? JsonLists.toList(jobEnrichment.getNormalizedSkillsJson()) : List.of();
        if (!normalized.isEmpty()) return dedupe(normalized);
        return dedupe(csv(job.getSkills()));
    }

    private static List<String> missingFrom(List<String> required, String evidence) {
        return required.stream().filter(s -> !evidence.contains(lower(s))).toList();
    }

    /** Keywords the JOB names (present in jobText) that the candidate's evidence lacks. */
    private static List<String> missingKeywords(List<String> keywords, String jobText, String evidence) {
        return presentKeywords(keywords, jobText).stream().filter(k -> !evidence.contains(k)).toList();
    }

    private static List<String> presentKeywords(List<String> keywords, String jobText) {
        return keywords.stream().filter(jobText::contains).toList();
    }

    private static List<String> missingCertifications(String jobText, CandidateProfile profile, String evidence) {
        if (!jobText.contains("certif")) return List.of();
        List<String> declared = profile != null ? JsonLists.toList(profile.getCertificationsJson()) : List.of();
        if (!declared.isEmpty()) return List.of();
        // The job asks for certification(s) and the candidate declares none — flag the cloud/tech
        // certs the job text names, or a generic marker if none are individually identifiable.
        List<String> named = presentKeywords(CLOUD_KEYWORDS, jobText).stream()
                .filter(k -> !evidence.contains(k)).map(k -> k + " certification").toList();
        return named.isEmpty() ? List.of("certification required by job") : named;
    }

    private static List<String> missingDomains(JobAiEnrichment jobEnrichment, CandidateProfile profile, String evidence) {
        if (jobEnrichment == null) return List.of();
        Set<String> candidateDomains = (profile != null ? JsonLists.toList(profile.getDomainsJson()) : List.<String>of())
                .stream().map(GapAnalysisService::lower).collect(Collectors.toSet());
        return JsonLists.toList(jobEnrichment.getDomainsJson()).stream()
                .filter(d -> !candidateDomains.contains(lower(d)) && !evidence.contains(lower(d)))
                .toList();
    }

    private static List<String> dedupe(List<String> xs) {
        return xs.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
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
