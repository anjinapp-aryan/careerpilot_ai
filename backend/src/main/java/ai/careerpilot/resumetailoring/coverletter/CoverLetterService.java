package ai.careerpilot.resumetailoring.coverletter;

import ai.careerpilot.ai.AiGatewayProperties;
import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.domain.*;
import ai.careerpilot.repo.*;
import ai.careerpilot.resumetailoring.coverletter.CoverLetterValidator.ValidationResult;
import ai.careerpilot.resumetailoring.event.CoverLetterCompletedEvent;
import ai.careerpilot.service.profile.JsonLists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2D.5 — generates a personalized cover letter grounded in the tailored resume, candidate
 * profile, and job posting. The system prompt hard-forbids fabricating skills / experience /
 * certifications / companies / titles, and {@link CoverLetterValidator} mechanically rejects the
 * highest-risk fabrication class (credential claims) plus generic/wrong-company letters — a
 * rejected letter is audited and NEVER persisted, exactly like Resume Tailoring's validator
 * contract (which caught a real hallucination live in the 2D.1 canary).
 *
 * <p>Versioned v1.N per (user, job): head row in {@code cover_letter}, immutable history in
 * {@code cover_letter_versions}, outcomes in {@code cover_letter_audit}. On success, publishes
 * {@link CoverLetterCompletedEvent} for the next stage (2D.6). Never throws; flag-gated dark by
 * {@code cover.letter.enabled}.
 */
@Service
public class CoverLetterService {

    private static final Logger log = LoggerFactory.getLogger(CoverLetterService.class);
    private static final int MAX_RESUME = 6000;
    private static final int MAX_JD = 3000;

    private static final String SYSTEM_PROMPT = """
            You are an expert career writing assistant. Write a personalized, professional cover
            letter for the candidate and job below.

            STRICT RULES — NEVER violate:
            - NEVER invent skills, technologies, certifications, employers, job titles, projects,
              metrics, or experience not present in the candidate's resume or profile below.
            - Only reference companies from the candidate's actual work history, plus the target
              company being applied to.
            - Ground every claim in the provided resume/profile content. You may reword, emphasize,
              and reorganize — never fabricate.

            STYLE: 3-5 short paragraphs, professional but warm, addressed to the hiring team at the
            target company, naming the exact role title. Mention the target company by name. Plain
            text only — no markdown, no placeholders like [Name].""";

    private final ResumeTailoringRepository tailorings;
    private final ResumeRepository resumes;
    private final JobRepository jobs;
    private final ApplicationRepository applications;
    private final CandidateProfileRepository profiles;
    private final CandidateProfileVersionRepository profileVersions;
    private final CandidateBehaviorProfileRepository behaviorProfiles;
    private final CoverLetterRepository coverLetters;
    private final CoverLetterVersionRepository versions;
    private final CoverLetterAuditRepository audit;
    private final CoverLetterValidator validator;
    private final CoverLetterCache cache;
    private final CoverLetterMetrics metrics;
    private final AiGatewayService ai;
    private final AiGatewayProperties aiProps;
    private final ApplicationEventPublisher events;
    private final boolean enabled;

    public CoverLetterService(ResumeTailoringRepository tailorings, ResumeRepository resumes,
                              JobRepository jobs, ApplicationRepository applications,
                              CandidateProfileRepository profiles,
                              CandidateProfileVersionRepository profileVersions,
                              CandidateBehaviorProfileRepository behaviorProfiles,
                              CoverLetterRepository coverLetters, CoverLetterVersionRepository versions,
                              CoverLetterAuditRepository audit, CoverLetterValidator validator,
                              CoverLetterCache cache, CoverLetterMetrics metrics,
                              AiGatewayService ai, AiGatewayProperties aiProps,
                              ApplicationEventPublisher events,
                              @Value("${cover.letter.enabled:false}") boolean enabled) {
        this.tailorings = tailorings;
        this.resumes = resumes;
        this.jobs = jobs;
        this.applications = applications;
        this.profiles = profiles;
        this.profileVersions = profileVersions;
        this.behaviorProfiles = behaviorProfiles;
        this.coverLetters = coverLetters;
        this.versions = versions;
        this.audit = audit;
        this.validator = validator;
        this.cache = cache;
        this.metrics = metrics;
        this.ai = ai;
        this.aiProps = aiProps;
        this.events = events;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Generate (or return the cached) cover letter for (userId, jobId). Empty when disabled/missing/rejected/failed. */
    @Transactional
    public Optional<CoverLetter> generate(UUID userId, UUID jobId, UUID resumeTailoringIdHint) {
        if (!enabled) return Optional.empty();
        long start = System.currentTimeMillis();
        metrics.recordRequest();
        try {
            ResumeTailoring tailoring = resumeTailoringIdHint != null
                    ? tailorings.findById(resumeTailoringIdHint).orElse(null)
                    : tailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId).orElse(null);
            Job job = jobs.findById(jobId).orElse(null);
            if (tailoring == null || job == null) {
                metrics.recordFailure();
                record(userId, jobId, null, null, CoverLetterAuditEntry.OUTCOME_ERROR, "missing tailoring or job");
                return Optional.empty();
            }

            Optional<UUID> cached = cache.get(userId, jobId, tailoring.getId());
            if (cached.isPresent()) {
                Optional<CoverLetter> existing = coverLetters.findById(cached.get());
                if (existing.isPresent()) {
                    record(userId, jobId, existing.get().getId(), existing.get().getVersion(),
                            CoverLetterAuditEntry.OUTCOME_CACHE_HIT, null);
                    metrics.recordSuccess();
                    return existing;
                }
            }

            CandidateProfile profile = profiles.findByUserId(userId).orElse(null);
            String originalText = resumes.findByUserIdOrderByCreatedAtDesc(userId).stream()
                    .findFirst().map(Resume::getParsedText).orElse("");
            List<String> declaredCerts = profile != null ? JsonLists.toList(profile.getCertificationsJson()) : List.of();

            List<String> preferred = aiProps.getRouting().getOrDefault("coverLetter", List.of());
            String content = ai.chat(List.of(ChatMessage.user(buildUserPrompt(tailoring, job, profile))),
                    SYSTEM_PROMPT, preferred);
            String provider = ai.getLastUsedProvider();
            metrics.recordProviderUsed(provider);

            ValidationResult validation = validator.validate(content, job.getCompany(), originalText, declaredCerts);
            if (!validation.valid()) {
                metrics.recordFailure();
                record(userId, jobId, null, null, CoverLetterAuditEntry.OUTCOME_VALIDATION_REJECTED, validation.reason());
                log.warn("COVER_LETTER rejected user={} job={} reason={}", userId, jobId, validation.reason());
                return Optional.empty();
            }

            UUID profileVersionId = profileVersions.findByUserIdOrderByCreatedAtDesc(userId).stream()
                    .findFirst().map(CandidateProfileVersion::getId).orElse(null);
            Instant behaviorVersion = behaviorProfiles.findById(userId)
                    .map(CandidateBehaviorProfile::getUpdatedAt).orElse(null);
            UUID applicationId = applications.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)
                    .map(Application::getId).orElse(null);

            CoverLetter head = coverLetters.findByUserIdAndJobId(userId, jobId).orElse(null);
            int nextVersion = head == null ? 1 : head.getVersion() + 1;
            if (head == null) {
                head = CoverLetter.builder()
                        .userId(userId).jobId(jobId)
                        .build();
            }
            head.setApplicationId(applicationId);
            head.setResumeTailoringId(tailoring.getId());
            head.setCandidateProfileVersion(profileVersionId);
            head.setBehaviorProfileVersion(behaviorVersion);
            head.setVersion(nextVersion);
            head.setProvider(provider);
            head.setStatus(CoverLetter.STATUS_GENERATED);
            head.setContent(content);
            CoverLetter saved = coverLetters.save(head);

            versions.save(CoverLetterVersion.builder()
                    .coverLetterId(saved.getId())
                    .userId(userId).jobId(jobId)
                    .resumeTailoringId(tailoring.getId())
                    .candidateProfileVersion(profileVersionId)
                    .behaviorProfileVersion(behaviorVersion)
                    .version(nextVersion)
                    .provider(provider)
                    .status(CoverLetter.STATUS_GENERATED)
                    .content(content)
                    .build());

            cache.put(userId, jobId, tailoring.getId(), saved.getId());
            record(userId, jobId, saved.getId(), nextVersion, CoverLetterAuditEntry.OUTCOME_GENERATED, null);
            metrics.recordSuccess();
            log.info("COVER_LETTER generated user={} job={} version=v1.{} provider={}",
                    userId, jobId, nextVersion, provider);
            events.publishEvent(new CoverLetterCompletedEvent(userId, jobId, tailoring.getId(),
                    saved.getId(), nextVersion));
            return Optional.of(saved);
        } catch (Exception e) {
            metrics.recordFailure();
            record(userId, jobId, null, null, CoverLetterAuditEntry.OUTCOME_ERROR, e.toString());
            log.warn("COVER_LETTER error user={} job={}: {}", userId, jobId, e.toString());
            return Optional.empty();
        } finally {
            metrics.recordLatency(System.currentTimeMillis() - start);
        }
    }

    public Optional<CoverLetter> latest(UUID userId, UUID jobId) {
        return coverLetters.findByUserIdAndJobId(userId, jobId);
    }

    private String buildUserPrompt(ResumeTailoring tailoring, Job job, CandidateProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("TARGET_ROLE: ").append(job.getTitle()).append('\n');
        sb.append("TARGET_COMPANY: ").append(job.getCompany()).append('\n');
        if (profile != null) {
            sb.append("CANDIDATE_SKILLS: ").append(String.join(", ", JsonLists.toList(profile.getSkillsJson()))).append('\n');
            sb.append("CANDIDATE_CERTIFICATIONS: ").append(String.join(", ", JsonLists.toList(profile.getCertificationsJson()))).append('\n');
            if (profile.getYearsExperience() != null) {
                sb.append("CANDIDATE_YEARS_EXPERIENCE: ").append(profile.getYearsExperience()).append('\n');
            }
        }
        sb.append("\nJOB_DESCRIPTION:\n").append(truncate(job.getDescription(), MAX_JD));
        sb.append("\n\nCANDIDATE_TAILORED_RESUME:\n").append(truncate(tailoring.getTailoredResumeText(), MAX_RESUME));
        return sb.toString();
    }

    private void record(UUID userId, UUID jobId, UUID coverLetterId, Integer version, String outcome, String reason) {
        audit.save(CoverLetterAuditEntry.builder()
                .userId(userId).jobId(jobId)
                .coverLetterId(coverLetterId).version(version)
                .outcome(outcome).reason(reason)
                .build());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
