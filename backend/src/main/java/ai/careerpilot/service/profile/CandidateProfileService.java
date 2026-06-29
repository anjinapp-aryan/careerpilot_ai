package ai.careerpilot.service.profile;

import ai.careerpilot.api.dto.CandidateProfileDto;
import ai.careerpilot.api.dto.CandidateProfileHistoryDto;
import ai.careerpilot.api.dto.CandidatePreferencesDto;
import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.CandidateProfileVersion;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.CandidateProfileVersionRepository;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.service.CandidatePreferencesService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates the canonical Candidate Intelligence Profile: AI-derived resume intelligence
 * merged with a snapshot of the editable {@link CandidatePreferencesService}. The expensive AI
 * extraction is cached against the resume fingerprint, so a preferences-only change re-merges
 * WITHOUT a new LLM call (see {@link #onPreferencesChanged}).
 *
 * Every regeneration writes a before/after {@link CandidateProfileVersion} for audit/rollback.
 * Logging is content-free: only userId, resumeId, confidence, latency and counts — never resume
 * text or profile PII. This service produces the profile, which is consumed by job
 * matching/discovery via {@code CandidateSignalResolver} once the relevant feature flag is on.
 */
@Service
public class CandidateProfileService {

    private static final Logger log = LoggerFactory.getLogger(CandidateProfileService.class);

    public static final String REASON_PREFERENCES_UPDATED = "PREFERENCES_UPDATED";
    public static final String REASON_MANUAL_REBUILD = "MANUAL_REBUILD";
    public static final String REASON_SCHEDULED_REBUILD = "SCHEDULED_REBUILD";

    private final CandidateProfileRepository profiles;
    private final CandidateProfileVersionRepository versions;
    private final ResumeRepository resumes;
    private final CandidatePreferencesService preferences;
    private final CandidateProfileExtractor extractor;
    private final CandidateProfileMetrics metrics;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public CandidateProfileService(CandidateProfileRepository profiles,
                                   CandidateProfileVersionRepository versions,
                                   ResumeRepository resumes,
                                   CandidatePreferencesService preferences,
                                   CandidateProfileExtractor extractor,
                                   CandidateProfileMetrics metrics) {
        this.profiles = profiles;
        this.versions = versions;
        this.resumes = resumes;
        this.preferences = preferences;
        this.extractor = extractor;
        this.metrics = metrics;
    }

    // ── Reads ───────────────────────────────────────────────────────────────────

    public Optional<CandidateProfileDto> get(UUID userId) {
        return profiles.findByUserId(userId).map(CandidateProfileDto::from);
    }

    public List<CandidateProfileHistoryDto> history(UUID userId) {
        return versions.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(v -> new CandidateProfileHistoryDto(
                        v.getReason(), v.getCreatedAt(),
                        readDto(v.getBeforeJson()), readDto(v.getAfterJson())))
                .toList();
    }

    // ── Triggers ──────────────────────────────────────────────────────────────

    /**
     * Resume uploaded/replaced/optimized → run a fresh AI extraction (LLM) and rebuild the
     * profile. Returns the new profile, or empty when extraction is not possible (no text).
     */
    @Transactional
    public Optional<CandidateProfileDto> onResumeChanged(UUID userId, UUID resumeId, String reason) {
        Resume resume = resumeId == null ? latestResume(userId) : resumes.findById(resumeId).orElse(null);
        if (resume == null || !userId.equals(resume.getUserId())) {
            log.info("Profile generation skipped: no owned resume (user={})", userId);
            return Optional.empty();
        }
        return regenerateFromResume(userId, resume, reason);
    }

    /**
     * Preferences saved → re-merge the preference snapshot into the CACHED resume intelligence
     * with NO LLM call. If no profile exists yet, create a preferences-only profile (AI fields
     * remain empty until a resume arrives). Cheap and frequent.
     */
    @Transactional
    public Optional<CandidateProfileDto> onPreferencesChanged(UUID userId) {
        metrics.recordPreferenceUpdate();
        CandidateProfile existing = profiles.findByUserId(userId).orElse(null);
        ResumeIntelligence ai = existing == null ? ResumeIntelligence.empty() : intelligenceFrom(existing);
        UUID resumeId = existing == null ? null : existing.getResumeId();
        String fingerprint = existing == null ? null : existing.getResumeFingerprint();

        CandidateProfileDto before = existing == null ? null : CandidateProfileDto.from(existing);
        CandidateProfile saved = upsert(userId, existing, ai, resumeId, fingerprint);
        writeVersion(userId, saved, before, REASON_PREFERENCES_UPDATED);
        log.info("Profile re-merged from preferences (no LLM) — user={}, resumeId={}", userId, resumeId);
        return Optional.of(CandidateProfileDto.from(saved));
    }

    /** Explicit force-regeneration from the latest resume (always runs the LLM). */
    @Transactional
    public Optional<CandidateProfileDto> rebuild(UUID userId) {
        metrics.recordRebuildRequest();
        Resume resume = latestResume(userId);
        if (resume == null) {
            // No resume to extract from — fall back to a preferences-only refresh.
            return onPreferencesChanged(userId);
        }
        return regenerateFromResume(userId, resume, REASON_MANUAL_REBUILD);
    }

    // ── Backfill (one-time enablement for existing users) ──────────────────────────────

    public enum BackfillOutcome { GENERATED, SKIPPED_CURRENT, SKIPPED_NO_RESUME, FAILED }

    /**
     * Would a backfill run actually generate a profile for this user? True only when the user has a
     * resume AND no profile yet, or a profile whose cached extraction is stale relative to the latest
     * resume. Pure check — never calls the LLM — so the backfill dry-run can size the work for free.
     */
    public boolean needsBackfill(UUID userId) {
        Resume resume = latestResume(userId);
        if (resume == null) return false;
        CandidateProfile existing = profiles.findByUserId(userId).orElse(null);
        if (existing == null) return true;
        String fp = fingerprint(resume.getParsedText());
        return fp == null || !fp.equals(existing.getResumeFingerprint());
    }

    /** Backfill one user idempotently with the standard manual-rebuild reason. */
    @Transactional
    public BackfillOutcome backfillUser(UUID userId) {
        return backfillUser(userId, REASON_MANUAL_REBUILD);
    }

    /**
     * Backfill one user idempotently: skip (no LLM) when the cached extraction already matches the
     * latest resume, otherwise regenerate with the given audit {@code reason} (e.g.
     * {@link #REASON_MANUAL_REBUILD} for the admin one-time backfill, {@link #REASON_SCHEDULED_REBUILD}
     * for the weekly catch-up job). Safe to re-run — re-running a backfill that already completed is
     * all SKIPPED_CURRENT. Failure is isolated and reported, never thrown.
     */
    @Transactional
    public BackfillOutcome backfillUser(UUID userId, String reason) {
        Resume resume = latestResume(userId);
        if (resume == null) return BackfillOutcome.SKIPPED_NO_RESUME;
        CandidateProfile existing = profiles.findByUserId(userId).orElse(null);
        String fp = fingerprint(resume.getParsedText());
        if (existing != null && fp != null && fp.equals(existing.getResumeFingerprint())) {
            return BackfillOutcome.SKIPPED_CURRENT;   // idempotent: extraction already current
        }
        return regenerateFromResume(userId, resume, reason).isPresent()
                ? BackfillOutcome.GENERATED : BackfillOutcome.FAILED;
    }

    // ── Core regeneration ────────────────────────────────────────────────────────

    private Optional<CandidateProfileDto> regenerateFromResume(UUID userId, Resume resume, String reason) {
        long started = System.currentTimeMillis();
        metrics.recordGenerationAttempt();
        ResumeIntelligence ai;
        try {
            ai = extractor.extract(resume.getParsedText());
            metrics.recordGenerationSuccess();
        } catch (RuntimeException e) {
            metrics.recordGenerationFailure();
            log.warn("Profile extraction failed — user={}, resumeId={}, reason={}: {}",
                    userId, resume.getId(), reason, e.toString());
            return Optional.empty();   // never disturb any existing profile or the source flow
        } finally {
            metrics.recordLatency(System.currentTimeMillis() - started);
        }

        CandidateProfile existing = profiles.findByUserId(userId).orElse(null);
        CandidateProfileDto before = existing == null ? null : CandidateProfileDto.from(existing);
        CandidateProfile saved = upsert(userId, existing, ai, resume.getId(), fingerprint(resume.getParsedText()));
        writeVersion(userId, saved, before, reason);
        log.info("Profile generated — user={}, resumeId={}, reason={}, confidence={}, latencyMs={}",
                userId, resume.getId(), reason, saved.getConfidenceScore(),
                System.currentTimeMillis() - started);
        return Optional.of(CandidateProfileDto.from(saved));
    }

    /** Create-or-update the single profile row, merging AI intelligence with the preference snapshot. */
    private CandidateProfile upsert(UUID userId, CandidateProfile existing, ResumeIntelligence ai,
                                    UUID resumeId, String fingerprint) {
        CandidatePreferencesDto prefs = preferences.get(userId);
        CandidateProfile p = existing == null
                ? CandidateProfile.builder().userId(userId).build()
                : existing;

        // AI-derived (cached) half
        p.setResumeId(resumeId);
        p.setResumeFingerprint(fingerprint);
        p.setYearsExperience(ai.yearsExperience());
        p.setCurrentRole(ai.currentRole());
        p.setSeniorityLevel(ai.seniority());
        p.setSkillsJson(JsonLists.toJson(ai.skills()));
        p.setDomainsJson(JsonLists.toJson(ai.domains()));
        p.setLanguagesJson(JsonLists.toJson(ai.languages()));
        p.setProfileSummary(ai.profileSummary());
        p.setConfidenceScore(ai.confidenceScore());
        p.setTechnologiesJson(JsonLists.toJson(ai.technologies()));
        p.setCertificationsJson(JsonLists.toJson(ai.certifications()));
        p.setIndustriesJson(JsonLists.toJson(ai.industries()));
        p.setLeadershipExperience(ai.leadershipExperience());
        p.setCloudExpertise(ai.cloudExpertise());
        p.setCareerGoalsJson(JsonLists.toJson(ai.careerGoals()));

        // target_roles is canonical: user-specified preferred roles first, then AI-inferred.
        p.setTargetRolesJson(JsonLists.toJson(union(prefs.preferredRolesOrEmpty(), ai.targetRoles())));

        // Preference snapshot half (editable source stays in candidate_preferences)
        p.setHomeCountry(prefs.homeCountry());
        p.setPreferredCountriesJson(JsonLists.toJson(prefs.preferredCountries()));
        p.setPreferredCitiesJson(JsonLists.toJson(prefs.preferredCities()));
        p.setWorkModesJson(JsonLists.toJson(workModes(prefs)));
        p.setExcludedRolesJson(JsonLists.toJson(prefs.excludedRolesOrEmpty()));
        p.setVisaRequired(prefs.visaSponsorshipRequired());
        p.setSalaryMin(prefs.salaryExpectationMin());
        p.setSalaryTarget(prefs.salaryExpectationMax());
        p.setSalaryCurrency(prefs.salaryCurrency());

        return profiles.save(p);
    }

    private void writeVersion(UUID userId, CandidateProfile saved, CandidateProfileDto before, String reason) {
        versions.save(CandidateProfileVersion.builder()
                .userId(userId)
                .profileId(saved.getId())
                .beforeJson(writeJson(before))
                .afterJson(writeJson(CandidateProfileDto.from(saved)))
                .reason(reason)
                .build());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** Reconstruct the cached AI intelligence from a persisted profile (no LLM). */
    private ResumeIntelligence intelligenceFrom(CandidateProfile p) {
        return new ResumeIntelligence(
                p.getYearsExperience(), p.getCurrentRole(), p.getSeniorityLevel(),
                JsonLists.toList(p.getSkillsJson()),
                JsonLists.toList(p.getTargetRolesJson()),
                JsonLists.toList(p.getDomainsJson()),
                JsonLists.toList(p.getLanguagesJson()),
                p.getProfileSummary(),
                p.getConfidenceScore() == null ? BigDecimal.ZERO : p.getConfidenceScore(),
                JsonLists.toList(p.getTechnologiesJson()),
                JsonLists.toList(p.getCertificationsJson()),
                JsonLists.toList(p.getIndustriesJson()),
                p.getLeadershipExperience(),
                p.getCloudExpertise(),
                JsonLists.toList(p.getCareerGoalsJson()));
    }

    private Resume latestResume(UUID userId) {
        List<Resume> list = resumes.findByUserIdOrderByCreatedAtDesc(userId);
        return list.isEmpty() ? null : list.get(0);
    }

    private static List<String> workModes(CandidatePreferencesDto p) {
        List<String> modes = new ArrayList<>();
        if (p.remotePreference()) modes.add("Remote");
        if (p.hybridPreference()) modes.add("Hybrid");
        if (p.onsitePreference()) modes.add("Onsite");
        return modes;
    }

    private static List<String> union(List<String> first, List<String> second) {
        Set<String> seen = new LinkedHashSet<>();
        if (first != null) first.forEach(s -> { if (s != null && !s.isBlank()) seen.add(s.trim()); });
        if (second != null) second.forEach(s -> { if (s != null && !s.isBlank()) seen.add(s.trim()); });
        return new ArrayList<>(seen);
    }

    private static String fingerprint(String text) {
        if (text == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(CandidateProfileDto dto) {
        if (dto == null) return null;
        try {
            return mapper.writeValueAsString(dto);
        } catch (Exception e) {
            return null;
        }
    }

    private CandidateProfileDto readDto(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, CandidateProfileDto.class);
        } catch (Exception e) {
            return null;
        }
    }
}
