package ai.careerpilot.resumetailoring.apppackage;

import ai.careerpilot.domain.*;
import ai.careerpilot.repo.*;
import ai.careerpilot.resumetailoring.event.ApplicationPackageReadyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2D.6 — assembles the complete application package for (user, job) by REFERENCE: it never
 * copies artifact content, it records exactly which row of each upstream artifact (tailored
 * resume, cover letter, ATS analysis, gap analysis, explainability) this package version points
 * at, plus profile/behavior/recommendation lineage. Deterministic — no LLM.
 *
 * <p>The core artifact is the tailored resume: without it there is no package (empty result).
 * Everything else is optional; a package missing optional artifacts is persisted with status
 * {@code INCOMPLETE} and the metadata JSON lists what's absent, so downstream (2D.7) can decide
 * whether it is safe to auto-apply. On success publishes {@link ApplicationPackageReadyEvent}.
 * Never throws; flag-gated dark by {@code application.package.enabled}.
 */
@Service
public class ApplicationPackageService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationPackageService.class);

    private final ResumeTailoringRepository tailorings;
    private final CoverLetterRepository coverLetters;
    private final ResumeAtsAnalysisRepository atsAnalyses;
    private final ResumeGapAnalysisRepository gapAnalyses;
    private final ResumeAtsExplanationRepository explanations;
    private final ApplicationRepository applications;
    private final CandidateProfileVersionRepository profileVersions;
    private final CandidateBehaviorProfileRepository behaviorProfiles;
    private final RecommendationAuditRepository recommendationAudit;
    private final ApplicationPackageRepository packages;
    private final ApplicationPackageVersionRepository versions;
    private final ApplicationPackageAuditRepository audit;
    private final ApplicationPackageCache cache;
    private final ApplicationPackageMetrics metrics;
    private final ApplicationEventPublisher events;
    private final boolean enabled;

    public ApplicationPackageService(ResumeTailoringRepository tailorings, CoverLetterRepository coverLetters,
                                     ResumeAtsAnalysisRepository atsAnalyses,
                                     ResumeGapAnalysisRepository gapAnalyses,
                                     ResumeAtsExplanationRepository explanations,
                                     ApplicationRepository applications,
                                     CandidateProfileVersionRepository profileVersions,
                                     CandidateBehaviorProfileRepository behaviorProfiles,
                                     RecommendationAuditRepository recommendationAudit,
                                     ApplicationPackageRepository packages,
                                     ApplicationPackageVersionRepository versions,
                                     ApplicationPackageAuditRepository audit,
                                     ApplicationPackageCache cache, ApplicationPackageMetrics metrics,
                                     ApplicationEventPublisher events,
                                     @Value("${application.package.enabled:false}") boolean enabled) {
        this.tailorings = tailorings;
        this.coverLetters = coverLetters;
        this.atsAnalyses = atsAnalyses;
        this.gapAnalyses = gapAnalyses;
        this.explanations = explanations;
        this.applications = applications;
        this.profileVersions = profileVersions;
        this.behaviorProfiles = behaviorProfiles;
        this.recommendationAudit = recommendationAudit;
        this.packages = packages;
        this.versions = versions;
        this.audit = audit;
        this.cache = cache;
        this.metrics = metrics;
        this.events = events;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Assemble (or return the cached) package for (userId, jobId). Empty when disabled, no tailoring, or failure. */
    @Transactional
    public Optional<ApplicationPackage> assemble(UUID userId, UUID jobId) {
        if (!enabled) return Optional.empty();
        long start = System.currentTimeMillis();
        metrics.recordRequest();
        try {
            ResumeTailoring tailoring = tailorings
                    .findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId).orElse(null);
            if (tailoring == null) {
                metrics.recordFailure();
                record(userId, jobId, null, null, ApplicationPackageAuditEntry.OUTCOME_ERROR, "no tailored resume yet");
                return Optional.empty();
            }
            CoverLetter coverLetter = coverLetters.findByUserIdAndJobId(userId, jobId).orElse(null);

            Optional<UUID> cached = cache.get(userId, jobId, tailoring.getId(),
                    coverLetter != null ? coverLetter.getId() : null);
            if (cached.isPresent()) {
                Optional<ApplicationPackage> existing = packages.findById(cached.get());
                if (existing.isPresent()) {
                    metrics.recordSuccess();
                    return existing;
                }
            }

            ResumeAtsAnalysis ats = atsAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId).orElse(null);
            ResumeGapAnalysis gap = gapAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId).orElse(null);
            ResumeAtsExplanation explanation = explanations.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId).orElse(null);
            UUID applicationId = applications.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)
                    .map(Application::getId).orElse(null);
            UUID profileVersionId = profileVersions.findByUserIdOrderByCreatedAtDesc(userId).stream()
                    .findFirst().map(CandidateProfileVersion::getId).orElse(null);
            Instant behaviorVersion = behaviorProfiles.findById(userId)
                    .map(CandidateBehaviorProfile::getUpdatedAt).orElse(null);
            UUID recommendationAuditId = tailoring.getRecommendationAuditId() != null
                    ? tailoring.getRecommendationAuditId()
                    : recommendationAudit.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)
                            .map(RecommendationAudit::getId).orElse(null);

            boolean complete = coverLetter != null && ats != null && gap != null && explanation != null;
            String status = complete ? ApplicationPackage.STATUS_ASSEMBLED : ApplicationPackage.STATUS_INCOMPLETE;

            ApplicationPackage head = packages.findByUserIdAndJobId(userId, jobId).orElse(null);
            int nextVersion = head == null ? 1 : head.getPackageVersion() + 1;
            if (head == null) {
                head = ApplicationPackage.builder().userId(userId).jobId(jobId).build();
            }
            head.setApplicationId(applicationId);
            head.setResumeId(tailoring.getOriginalResumeId());
            head.setResumeTailoringId(tailoring.getId());
            head.setCoverLetterId(coverLetter != null ? coverLetter.getId() : null);
            head.setAtsAnalysisId(ats != null ? ats.getId() : null);
            head.setGapAnalysisId(gap != null ? gap.getId() : null);
            head.setAtsExplanationId(explanation != null ? explanation.getId() : null);
            head.setCandidateProfileVersion(profileVersionId);
            head.setBehaviorProfileVersion(behaviorVersion);
            head.setRecommendationAuditId(recommendationAuditId);
            head.setPackageVersion(nextVersion);
            head.setStatus(status);
            head.setMetadata(buildMetadata(tailoring, coverLetter, ats, gap, explanation));
            ApplicationPackage saved = packages.save(head);

            versions.save(ApplicationPackageVersion.builder()
                    .applicationPackageId(saved.getId())
                    .userId(userId).jobId(jobId)
                    .resumeId(saved.getResumeId())
                    .resumeTailoringId(saved.getResumeTailoringId())
                    .coverLetterId(saved.getCoverLetterId())
                    .atsAnalysisId(saved.getAtsAnalysisId())
                    .gapAnalysisId(saved.getGapAnalysisId())
                    .atsExplanationId(saved.getAtsExplanationId())
                    .candidateProfileVersion(profileVersionId)
                    .behaviorProfileVersion(behaviorVersion)
                    .recommendationAuditId(recommendationAuditId)
                    .packageVersion(nextVersion)
                    .status(status)
                    .metadata(saved.getMetadata())
                    .build());

            cache.put(userId, jobId, tailoring.getId(), coverLetter != null ? coverLetter.getId() : null, saved.getId());
            record(userId, jobId, saved.getId(), nextVersion,
                    complete ? ApplicationPackageAuditEntry.OUTCOME_ASSEMBLED : ApplicationPackageAuditEntry.OUTCOME_INCOMPLETE,
                    complete ? null : missingSummary(coverLetter, ats, gap, explanation));
            if (complete) metrics.recordSuccess(); else metrics.recordIncomplete();
            log.info("APP_PACKAGE assembled user={} job={} version={} status={}", userId, jobId, nextVersion, status);
            events.publishEvent(new ApplicationPackageReadyEvent(userId, jobId, saved.getId(), nextVersion));
            return Optional.of(saved);
        } catch (Exception e) {
            metrics.recordFailure();
            record(userId, jobId, null, null, ApplicationPackageAuditEntry.OUTCOME_ERROR, e.toString());
            log.warn("APP_PACKAGE error user={} job={}: {}", userId, jobId, e.toString());
            return Optional.empty();
        } finally {
            metrics.recordLatency(System.currentTimeMillis() - start);
        }
    }

    public Optional<ApplicationPackage> latest(UUID userId, UUID jobId) {
        return packages.findByUserIdAndJobId(userId, jobId);
    }

    /** Compact JSON summary — ids + statuses only, no artifact content (content lives in its own table). */
    private static String buildMetadata(ResumeTailoring tailoring, CoverLetter coverLetter,
                                        ResumeAtsAnalysis ats, ResumeGapAnalysis gap,
                                        ResumeAtsExplanation explanation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resume", Map.of("tailoringId", String.valueOf(tailoring.getId()),
                "version", "v1." + tailoring.getTailoringVersion()));
        m.put("coverLetter", coverLetter == null ? null
                : Map.of("id", String.valueOf(coverLetter.getId()), "version", "v1." + coverLetter.getVersion()));
        m.put("atsAnalysis", ats == null ? null
                : Map.of("id", String.valueOf(ats.getId()), "atsScore", ats.getAtsScore()));
        m.put("gapAnalysis", gap == null ? null
                : Map.of("id", String.valueOf(gap.getId()), "gapScore", gap.getGapScore()));
        m.put("explainability", explanation == null ? null
                : Map.of("id", String.valueOf(explanation.getId())));
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            sb.append(e.getValue() == null ? "null" : toJson(e.getValue()));
        }
        return sb.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private static String toJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) map).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(e.getKey()).append("\":").append(toJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (value instanceof Number) return String.valueOf(value);
        return '"' + String.valueOf(value) + '"';
    }

    private static String missingSummary(CoverLetter coverLetter, ResumeAtsAnalysis ats,
                                         ResumeGapAnalysis gap, ResumeAtsExplanation explanation) {
        StringBuilder sb = new StringBuilder("missing:");
        if (coverLetter == null) sb.append(" coverLetter");
        if (ats == null) sb.append(" atsAnalysis");
        if (gap == null) sb.append(" gapAnalysis");
        if (explanation == null) sb.append(" explainability");
        return sb.toString();
    }

    private void record(UUID userId, UUID jobId, UUID packageId, Integer version, String outcome, String reason) {
        audit.save(ApplicationPackageAuditEntry.builder()
                .userId(userId).jobId(jobId)
                .applicationPackageId(packageId).packageVersion(version)
                .outcome(outcome).reason(reason)
                .build());
    }
}
