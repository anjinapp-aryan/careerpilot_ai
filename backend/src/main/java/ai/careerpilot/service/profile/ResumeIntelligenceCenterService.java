package ai.careerpilot.service.profile;

import ai.careerpilot.api.dto.CandidateProfileDto;
import ai.careerpilot.api.dto.CandidateProfileHistoryDto;
import ai.careerpilot.api.dto.ResumeIntelligenceDtos.ResumeAnalysisHistoryEntryDto;
import ai.careerpilot.api.dto.ResumeIntelligenceDtos.ResumeAnalysisStatusDto;
import ai.careerpilot.api.dto.ResumeIntelligenceDtos.ResumeDashboardEntryDto;
import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.domain.ResumeAnalysisRun;
import ai.careerpilot.domain.WorkflowRun;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.ResumeAnalysisRunRepository;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.repo.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 8.2 — Resume Intelligence Center. This is deliberately NOT a new parsing/extraction
 * engine: every actual analysis is still done by {@link CandidateProfileService} /
 * {@link CandidateProfileExtractor}, exactly as before this feature existed. This service is
 * purely the orchestration + status-tracking layer the spec calls for — "the UI + API for manual
 * control and visibility" — wrapping the existing pipeline with a per-resume lifecycle
 * (NOT_ANALYZED/ANALYZING/ANALYZED/OUTDATED/FAILED/PARTIAL) that nothing in the codebase tracked
 * before.
 *
 * <p>Architecture note: {@link CandidateProfile} stays exactly what it always was — one canonical,
 * latest-wins row per user. This service does not introduce a second per-resume profile store; it
 * only adds {@link ResumeAnalysisRun} rows to record the outcome of each analyze attempt, and
 * derives NOT_ANALYZED/OUTDATED/PARTIAL by comparing those rows against the current profile's
 * {@code resumeId} — never persisted as their own states.
 *
 * <p>ATS score: there is no honest, job-less ATS score for a bare upload — the existing scorer
 * ({@code AtsOptimizationService}/{@code ResumeImprovementCalculator}) always requires a specific
 * job's skill list to compare against. Rather than fabricate a target, {@link #atsScoreFor} only
 * surfaces a score when a completed LangGraph AI Workflow run exists for this exact resume
 * (which computed one for real); otherwise it is honestly {@code null}.
 */
@Service
public class ResumeIntelligenceCenterService {

    private static final Logger log = LoggerFactory.getLogger(ResumeIntelligenceCenterService.class);

    /** Below this cached confidence, an ANALYZED resume is surfaced as PARTIAL, not ANALYZED. */
    private static final BigDecimal PARTIAL_CONFIDENCE_THRESHOLD = BigDecimal.valueOf(0.5);

    private final ResumeRepository resumes;
    private final ResumeAnalysisRunRepository runs;
    private final CandidateProfileRepository profiles;
    private final CandidateProfileService profileService;
    private final WorkflowRunRepository workflowRuns;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean enabled;

    public ResumeIntelligenceCenterService(ResumeRepository resumes,
                                           ResumeAnalysisRunRepository runs,
                                           CandidateProfileRepository profiles,
                                           CandidateProfileService profileService,
                                           WorkflowRunRepository workflowRuns,
                                           @Value("${resume.intelligence.center.enabled:false}") boolean enabled) {
        this.resumes = resumes;
        this.runs = runs;
        this.profiles = profiles;
        this.profileService = profileService;
        this.workflowRuns = workflowRuns;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ── Reads ───────────────────────────────────────────────────────────────────

    public List<ResumeDashboardEntryDto> dashboard(UUID userId) {
        return resumes.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(r -> new ResumeDashboardEntryDto(
                        r.getId(), r.getFilename(), r.getSizeBytes(), r.getCreatedAt(), statusFor(userId, r)))
                .toList();
    }

    public ResumeAnalysisStatusDto status(UUID userId, UUID resumeId) {
        Resume resume = resumes.findById(resumeId).orElseThrow();
        if (!resume.getUserId().equals(userId)) throw new SecurityException("forbidden");
        return statusFor(userId, resume);
    }

    /** The current canonical profile, only when this specific resume is what it was built from. */
    public Optional<CandidateProfileDto> analysis(UUID userId, UUID resumeId) {
        Resume resume = resumes.findById(resumeId).orElseThrow();
        if (!resume.getUserId().equals(userId)) throw new SecurityException("forbidden");
        return profiles.findByUserId(userId)
                .filter(p -> resumeId.equals(p.getResumeId()))
                .map(CandidateProfileDto::from);
    }

    /**
     * Profile version history entries where either side of the before/after snapshot was built
     * from this resume. Reuses {@link CandidateProfileService#history(UUID)} unchanged — CandidateProfile
     * stays one row per user, so this is a filter over the user's full history, not a second store.
     */
    public List<ResumeAnalysisHistoryEntryDto> history(UUID userId, UUID resumeId) {
        Resume resume = resumes.findById(resumeId).orElseThrow();
        if (!resume.getUserId().equals(userId)) throw new SecurityException("forbidden");
        return profileService.history(userId).stream()
                .filter(h -> matchesResume(h, resumeId))
                .map(h -> new ResumeAnalysisHistoryEntryDto(h.reason(), h.createdAt(), h.before(), h.after()))
                .toList();
    }

    private static boolean matchesResume(CandidateProfileHistoryDto h, UUID resumeId) {
        return (h.after() != null && resumeId.equals(h.after().resumeId()))
                || (h.before() != null && resumeId.equals(h.before().resumeId()));
    }

    // ── Triggers ────────────────────────────────────────────────────────────────

    /**
     * Analyze (or re-analyze — same operation) a specific resume. Synchronous, matching the
     * existing precedent set by {@code POST /api/candidate-profile/rebuild}: records an ANALYZING
     * run, delegates 100% of the extraction to {@link CandidateProfileService#onResumeChanged},
     * then records ANALYZED/FAILED. No new business logic — only status bookkeeping around the
     * existing call.
     */
    @Transactional
    public ResumeAnalysisStatusDto analyze(UUID userId, UUID resumeId) {
        Resume resume = resumes.findById(resumeId).orElseThrow();
        if (!resume.getUserId().equals(userId)) throw new SecurityException("forbidden");

        Instant started = Instant.now();
        ResumeAnalysisRun run = runs.save(ResumeAnalysisRun.builder()
                .userId(userId).resumeId(resumeId)
                .status(ResumeAnalysisRun.STATUS_ANALYZING)
                .startedAt(started)
                .build());

        Optional<CandidateProfileDto> result = profileService.onResumeChanged(
                userId, resumeId, CandidateProfileService.REASON_MANUAL_REBUILD);

        Instant completed = Instant.now();
        long durationMs = completed.toEpochMilli() - started.toEpochMilli();
        if (result.isPresent()) {
            run.setStatus(ResumeAnalysisRun.STATUS_ANALYZED);
            run.setCompletedAt(completed);
            run.setDurationMs(durationMs);
            runs.save(run);
            log.info("RESUME_INTELLIGENCE analyzed user={} resumeId={} durationMs={}", userId, resumeId, durationMs);
        } else {
            run.setStatus(ResumeAnalysisRun.STATUS_FAILED);
            run.setCompletedAt(completed);
            run.setDurationMs(durationMs);
            run.setErrorMessage("Extraction failed — see backend logs for the CandidateProfileService warning at this timestamp.");
            runs.save(run);
            log.warn("RESUME_INTELLIGENCE analyze failed user={} resumeId={}", userId, resumeId);
        }
        return statusFor(userId, resume);
    }

    // ── Status derivation ──────────────────────────────────────────────────────

    private ResumeAnalysisStatusDto statusFor(UUID userId, Resume resume) {
        UUID resumeId = resume.getId();
        List<ResumeAnalysisRun> resumeRuns = runs.findByUserIdAndResumeIdOrderByCreatedAtDesc(userId, resumeId);
        CandidateProfile profile = profiles.findByUserId(userId).orElse(null);
        Integer atsScore = atsScoreFor(userId, resumeId);

        if (resumeRuns.isEmpty()) {
            return new ResumeAnalysisStatusDto(resumeId, "NOT_ANALYZED", null, null, null, atsScore, null);
        }

        ResumeAnalysisRun last = resumeRuns.get(0);
        if (ResumeAnalysisRun.STATUS_ANALYZING.equals(last.getStatus())) {
            return new ResumeAnalysisStatusDto(resumeId, "ANALYZING", null, null, null, atsScore, null);
        }
        if (ResumeAnalysisRun.STATUS_FAILED.equals(last.getStatus())) {
            return new ResumeAnalysisStatusDto(
                    resumeId, "FAILED", last.getCompletedAt(), last.getDurationMs(), null, atsScore, last.getErrorMessage());
        }

        // Last attempt succeeded — but only ANALYZED/PARTIAL if this resume is still the profile's
        // current source. A later resume superseding it makes this analysis honestly OUTDATED.
        boolean isCurrent = profile != null && resumeId.equals(profile.getResumeId());
        if (!isCurrent) {
            return new ResumeAnalysisStatusDto(
                    resumeId, "OUTDATED", last.getCompletedAt(), last.getDurationMs(), null, atsScore, null);
        }
        BigDecimal confidence = profile.getConfidenceScore();
        String status = (confidence != null && confidence.compareTo(PARTIAL_CONFIDENCE_THRESHOLD) < 0)
                ? "PARTIAL" : "ANALYZED";
        return new ResumeAnalysisStatusDto(
                resumeId, status, last.getCompletedAt(), last.getDurationMs(), confidence, atsScore, null);
    }

    /**
     * Only ever returns a real, already-computed score (from a completed LangGraph run whose
     * state references this exact resume) — never invents one. See class Javadoc.
     */
    private Integer atsScoreFor(UUID userId, UUID resumeId) {
        for (WorkflowRun run : workflowRuns.findTop20ByUserIdOrderByCreatedAtDesc(userId)) {
            if (run.getAtsScore() == null) continue;
            Map<String, Object> state = parseState(run);
            Object stateResumeId = state.get("resume_id");
            if (stateResumeId != null && resumeId.toString().equals(stateResumeId.toString())) {
                return run.getAtsScore();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseState(WorkflowRun run) {
        try {
            return mapper.readValue(run.getState(), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
