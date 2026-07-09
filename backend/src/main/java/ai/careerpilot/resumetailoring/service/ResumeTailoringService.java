package ai.careerpilot.resumetailoring.service;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.domain.*;
import ai.careerpilot.learning.resume.LearningResumeOrdering;
import ai.careerpilot.repo.*;
import ai.careerpilot.resumetailoring.audit.ResumeTailoringAuditService;
import ai.careerpilot.resumetailoring.cache.ResumeTailoringCache;
import ai.careerpilot.resumetailoring.cache.ResumeTailoringCacheMetrics;
import ai.careerpilot.resumetailoring.llm.ResumeTailoringPromptBuilder;
import ai.careerpilot.resumetailoring.llm.ResumeTailoringValidator;
import ai.careerpilot.resumetailoring.llm.ResumeTailoringValidator.ValidationResult;
import ai.careerpilot.resumetailoring.llm.TailoringContext;
import ai.careerpilot.resumetailoring.scoring.ResumeImprovementCalculator;
import ai.careerpilot.resumetailoring.scoring.ResumeImprovementCalculator.ImprovementResult;
import ai.careerpilot.resumetailoring.version.ResumeVersionManager;
import ai.careerpilot.service.profile.JsonLists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2D.1 — orchestrates one resume-tailoring generation: assemble context from existing
 * Phase 1/2B/2C data (read-only), check the cache, call the LLM via the existing
 * {@link AiGatewayService}, validate (Step 7), score improvement (Step 8), persist a new
 * {@link ResumeTailoring} version, and audit the outcome. Never modifies the original
 * {@link Resume}. Flag-gated by {@code resume.tailoring.enabled} (default off) — when disabled,
 * every entry point is a no-op so this service can be safely wired into the approval flow with
 * zero behavior change until explicitly enabled.
 */
@Service
public class ResumeTailoringService {

    private static final Logger log = LoggerFactory.getLogger(ResumeTailoringService.class);

    private final ResumeRepository resumes;
    private final JobRepository jobs;
    private final CandidateProfileRepository profiles;
    private final CandidateProfileVersionRepository profileVersions;
    private final CandidateBehaviorProfileRepository behaviorProfiles;
    private final JobAiEnrichmentRepository enrichment;
    private final JobRecommendationExplanationRepository explanations;
    private final RecommendationAuditRepository recommendationAudit;
    private final ResumeTailoringRepository tailorings;
    private final ResumeTailoringPromptBuilder promptBuilder;
    private final ResumeTailoringValidator validator;
    private final ResumeImprovementCalculator improvementCalculator;
    private final ResumeVersionManager versionManager;
    private final ResumeTailoringCache cache;
    private final ResumeTailoringCacheMetrics metrics;
    private final ResumeTailoringAuditService audit;
    private final AiGatewayService ai;
    private final LearningResumeOrdering learningOrdering;
    private final ai.careerpilot.companyintel.CompanyResumeHints companyHints;
    private final boolean enabled;
    private final List<String> preferredProviders;

    public ResumeTailoringService(ResumeRepository resumes, JobRepository jobs,
                                  CandidateProfileRepository profiles,
                                  CandidateProfileVersionRepository profileVersions,
                                  CandidateBehaviorProfileRepository behaviorProfiles,
                                  JobAiEnrichmentRepository enrichment,
                                  JobRecommendationExplanationRepository explanations,
                                  RecommendationAuditRepository recommendationAudit,
                                  ResumeTailoringRepository tailorings,
                                  ResumeTailoringPromptBuilder promptBuilder,
                                  ResumeTailoringValidator validator,
                                  ResumeImprovementCalculator improvementCalculator,
                                  ResumeVersionManager versionManager,
                                  ResumeTailoringCache cache,
                                  ResumeTailoringCacheMetrics metrics,
                                  ResumeTailoringAuditService audit,
                                  AiGatewayService ai,
                                  LearningResumeOrdering learningOrdering,
                                  ai.careerpilot.companyintel.CompanyResumeHints companyHints,
                                  @Value("${resume.tailoring.enabled:false}") boolean enabled,
                                  @Value("${resume.tailoring.preferred-providers:}") List<String> preferredProviders) {
        this.resumes = resumes;
        this.jobs = jobs;
        this.profiles = profiles;
        this.profileVersions = profileVersions;
        this.behaviorProfiles = behaviorProfiles;
        this.enrichment = enrichment;
        this.explanations = explanations;
        this.recommendationAudit = recommendationAudit;
        this.tailorings = tailorings;
        this.promptBuilder = promptBuilder;
        this.validator = validator;
        this.improvementCalculator = improvementCalculator;
        this.versionManager = versionManager;
        this.cache = cache;
        this.metrics = metrics;
        this.audit = audit;
        this.ai = ai;
        this.learningOrdering = learningOrdering;
        this.companyHints = companyHints;
        this.enabled = enabled;
        this.preferredProviders = preferredProviders;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Generate (or return the cached) tailored resume for (userId, jobId). Returns empty when the
     * feature is disabled, or when the user has no resume yet (nothing to tailor).
     */
    @Transactional
    public Optional<ResumeTailoring> tailor(UUID userId, UUID jobId, UUID recommendationAuditIdHint) {
        return tailor(userId, jobId, recommendationAuditIdHint, false);
    }

    private Optional<ResumeTailoring> tailor(UUID userId, UUID jobId, UUID recommendationAuditIdHint, boolean bypassCache) {
        if (!enabled) return Optional.empty();

        long start = System.currentTimeMillis();
        metrics.recordRequest();
        try {
            Resume resume = resumes.findByUserIdOrderByCreatedAtDesc(userId).stream().findFirst().orElse(null);
            Job job = jobs.findById(jobId).orElse(null);
            if (resume == null || job == null) {
                metrics.recordFailure();
                audit.record(userId, jobId, null, null, null, recommendationAuditIdHint, null,
                        ResumeTailoringAuditEntryOutcome.ERROR, "missing resume or job");
                return Optional.empty();
            }

            CandidateProfile profile = profiles.findByUserId(userId).orElse(null);
            UUID profileVersionId = profileVersions.findByUserIdOrderByCreatedAtDesc(userId).stream()
                    .findFirst().map(CandidateProfileVersion::getId).orElse(null);
            CandidateBehaviorProfile behavior = behaviorProfiles.findById(userId).orElse(null);
            JobAiEnrichment jobEnrichment = enrichment.findByJobId(jobId).orElse(null);
            JobRecommendationExplanation explanation = explanations.findByUserIdAndJobId(userId, jobId).orElse(null);
            UUID recommendationAuditId = recommendationAuditIdHint != null ? recommendationAuditIdHint
                    : recommendationAudit.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)
                            .map(RecommendationAudit::getId).orElse(null);

            Instant resumeVersion = resume.getUpdatedAt() != null ? resume.getUpdatedAt() : resume.getCreatedAt();
            Instant jobVersion = jobEnrichment != null && jobEnrichment.getUpdatedAt() != null
                    ? jobEnrichment.getUpdatedAt() : job.getCreatedAt();

            Optional<UUID> cached = bypassCache ? Optional.empty()
                    : cache.get(userId, jobId, resumeVersion, jobVersion, profileVersionId);
            if (cached.isPresent()) {
                Optional<ResumeTailoring> existing = tailorings.findById(cached.get());
                if (existing.isPresent()) {
                    audit.record(userId, jobId, existing.get().getId(), existing.get().getTailoringVersion(),
                            profileVersionId, recommendationAuditId, existing.get().getImprovementScore(),
                            ResumeTailoringAuditEntryOutcome.CACHE_HIT, null);
                    metrics.recordSuccess();
                    return existing;
                }
            }

            TailoringContext ctx = buildContext(userId, jobId, resume, job, profile, behavior, jobEnrichment,
                    explanation, recommendationAuditId, profileVersionId);

            // Phase 7.13 — observed company keyword demand from the knowledge graph; empty when the
            // graph is dark or the company is unknown, leaving the prompt byte-for-byte unchanged.
            List<String> companyKeywordHints = companyHints.keywordHints(userId, job.getCompany());
            String tailoredText = ai.chat(promptBuilder.buildMessages(ctx, companyKeywordHints),
                    promptBuilder.systemPrompt(), preferredProviders);
            metrics.recordProviderUsed(ai.getLastUsedProvider());

            List<String> declaredSkills = profile != null ? JsonLists.toList(profile.getSkillsJson()) : List.of();
            List<String> declaredTech = profile != null ? JsonLists.toList(profile.getTechnologiesJson()) : List.of();
            List<String> declaredCerts = profile != null ? JsonLists.toList(profile.getCertificationsJson()) : List.of();

            ValidationResult validation = validator.validate(tailoredText, resume.getParsedText(),
                    declaredSkills, declaredTech, declaredCerts);
            if (!validation.valid()) {
                metrics.recordFailure();
                audit.record(userId, jobId, null, null, profileVersionId, recommendationAuditId, null,
                        ResumeTailoringAuditEntryOutcome.VALIDATION_REJECTED, validation.reason());
                log.warn("RESUME_TAILORING rejected user={} job={} reason={}", userId, jobId, validation.reason());
                return Optional.empty();
            }

            List<String> jobSkills = ctx.jobSkills();
            ImprovementResult improvement = improvementCalculator.calculate(resume.getParsedText(), tailoredText, jobSkills);

            int nextVersion = versionManager.nextVersion(userId, jobId);
            ResumeTailoring saved = tailorings.save(ResumeTailoring.builder()
                    .userId(userId).jobId(jobId).originalResumeId(resume.getId())
                    .recommendationAuditId(recommendationAuditId)
                    .candidateProfileVersion(profileVersionId)
                    .behaviorProfileVersion(behavior != null ? behavior.getUpdatedAt() : null)
                    .tailoringVersion(nextVersion)
                    .tailoredResumeText(tailoredText)
                    .atsBefore(improvement.atsBefore())
                    .atsAfter(improvement.atsAfter())
                    .improvementScore(improvement.improvementScore())
                    .status(ResumeTailoring.STATUS_GENERATED)
                    .build());

            cache.put(userId, jobId, resumeVersion, jobVersion, profileVersionId, saved.getId());
            audit.record(userId, jobId, saved.getId(), nextVersion, profileVersionId, recommendationAuditId,
                    improvement.improvementScore(), ResumeTailoringAuditEntryOutcome.GENERATED, null);
            metrics.recordSuccess();
            log.info("RESUME_TAILORING generated user={} job={} version={} improvement={}",
                    userId, jobId, nextVersion, improvement.improvementScore());
            return Optional.of(saved);
        } catch (Exception e) {
            metrics.recordFailure();
            audit.record(userId, jobId, null, null, null, recommendationAuditIdHint, null,
                    ResumeTailoringAuditEntryOutcome.ERROR, e.toString());
            log.warn("RESUME_TAILORING error user={} job={}: {}", userId, jobId, e.toString());
            return Optional.empty();
        } finally {
            metrics.recordLatency(System.currentTimeMillis() - start);
        }
    }

    /** Force a fresh generation, bypassing the cache lookup (Step 11 rebuild endpoint). */
    @Transactional
    public Optional<ResumeTailoring> rebuild(UUID userId, UUID jobId) {
        return tailor(userId, jobId, null, true);
    }

    public Optional<ResumeTailoring> latest(UUID userId, UUID jobId) {
        return tailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId);
    }

    public List<ResumeTailoring> history(UUID userId) {
        return tailorings.findByUserIdOrderByCreatedAtDesc(userId);
    }

    private TailoringContext buildContext(UUID userId, UUID jobId, Resume resume, Job job, CandidateProfile profile,
                                          CandidateBehaviorProfile behavior, JobAiEnrichment jobEnrichment,
                                          JobRecommendationExplanation explanation, UUID recommendationAuditId,
                                          UUID profileVersionId) {
        List<String> jobSkills = jobEnrichment != null ? JsonLists.toList(jobEnrichment.getNormalizedSkillsJson()) : List.of();
        List<String> jobDomains = jobEnrichment != null ? JsonLists.toList(jobEnrichment.getDomainsJson()) : List.of();
        List<String> profileSkills = profile != null ? JsonLists.toList(profile.getSkillsJson()) : List.of();
        // Phase 6.5: reorder (never drop/invent) skills by learned success weight, most successful
        // first, so the LLM sees the candidate's historically-winning skills foregrounded. No-op when
        // learning.adaptive-resume.enabled is off.
        profileSkills = learningOrdering.orderSkills(userId, profileSkills);
        jobSkills = learningOrdering.orderSkills(userId, jobSkills);
        return new TailoringContext(
                userId, jobId, resume.getId(), resume.getParsedText(),
                recommendationAuditId, profileVersionId,
                behavior != null ? behavior.getUpdatedAt() : null,
                profileSkills,
                profile != null ? JsonLists.toList(profile.getTargetRolesJson()) : List.of(),
                profile != null ? profile.getYearsExperience() : null,
                profile != null ? JsonLists.toList(profile.getTechnologiesJson()) : List.of(),
                profile != null ? JsonLists.toList(profile.getCertificationsJson()) : List.of(),
                behavior != null ? csvToList(behavior.getPreferredRoles()) : List.of(),
                behavior != null ? csvToList(behavior.getPreferredWorkModes()) : List.of(),
                job.getTitle(), job.getCompany(), job.getDescription(), jobSkills,
                jobEnrichment != null ? jobEnrichment.getRoleFamily() : null,
                jobDomains.isEmpty() ? null : jobDomains.get(0),
                jobEnrichment != null ? jobEnrichment.getCountry() : job.getCountry(),
                explanation != null ? explanation.getMatchingSkills() : null,
                explanation != null ? explanation.getMissingSkills() : null,
                explanation != null ? explanation.getResumeImprovements() : null);
    }

    private static List<String> csvToList(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return List.of(csv.split(","));
    }

    /** Outcome constants, mirrored from {@link ResumeTailoringAuditEntry} for readability at call sites. */
    private static final class ResumeTailoringAuditEntryOutcome {
        static final String GENERATED = ResumeTailoringAuditEntry.OUTCOME_GENERATED;
        static final String VALIDATION_REJECTED = ResumeTailoringAuditEntry.OUTCOME_VALIDATION_REJECTED;
        static final String CACHE_HIT = ResumeTailoringAuditEntry.OUTCOME_CACHE_HIT;
        static final String ERROR = ResumeTailoringAuditEntry.OUTCOME_ERROR;
    }
}
