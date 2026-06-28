package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.WorkflowRun;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.WorkflowRunRepository;
import ai.careerpilot.service.CandidatePreferencesService;
import ai.careerpilot.service.profile.JsonLists;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the candidate signals the matcher scores against, from <b>one authoritative source</b>.
 *
 * <p>Phase 1 makes the canonical {@link CandidateProfile} that source. When
 * {@code jobs.matching.profile-source-enabled} is on AND the user has a profile row, every signal
 * (skills, target role, locations, experience, location/salary/visa/work-mode preferences, excluded
 * roles) is read from the profile and nothing else — the profile is the single source of truth.
 *
 * <p>Otherwise (flag off, or no profile yet) it falls back to the legacy path: the user's latest
 * {@link WorkflowRun} state blob merged with live {@code candidate_preferences}. This fallback is
 * byte-for-byte the behavior that shipped before Phase 1, so flipping the flag is a pure, reversible
 * source swap with zero regression for users who have no profile.
 *
 * <p>Excluded roles are surfaced in <i>both</i> modes (the legacy path reads them straight from
 * preferences), so the exclusion filter works regardless of the source flag — an empty list is a
 * no-op, which is why it is safe to ship active.
 */
@Component
public class CandidateSignalResolver {

    private static final Logger log = LoggerFactory.getLogger(CandidateSignalResolver.class);

    private final WorkflowRunRepository runs;
    private final CandidateProfileRepository profiles;
    private final CandidatePreferencesService preferences;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean profileSourceEnabled;

    public CandidateSignalResolver(WorkflowRunRepository runs,
                                   CandidateProfileRepository profiles,
                                   CandidatePreferencesService preferences,
                                   @Value("${jobs.matching.profile-source-enabled:false}") boolean profileSourceEnabled) {
        this.runs = runs;
        this.profiles = profiles;
        this.preferences = preferences;
        this.profileSourceEnabled = profileSourceEnabled;
    }

    /** Candidate signals for the matcher, plus provenance for observability. */
    public record CandidateMatchSignals(List<String> skills, String targetRole,
                                        List<String> targetLocations, Integer yearsExperience,
                                        Integer atsScore, JobScoring.PreferenceContext preferences,
                                        List<String> excludedRoles, UUID resumeId, String source) {}

    /**
     * Resolve signals for a user, or empty when there is nothing to score against (no profile and
     * no workflow run). The matcher treats empty as a no-op (writes no recommendations).
     */
    public Optional<CandidateMatchSignals> resolve(UUID userId) {
        if (profileSourceEnabled) {
            Optional<CandidateProfile> profile = profiles.findByUserId(userId);
            if (profile.isPresent()) {
                return Optional.of(fromProfile(profile.get()));
            }
            // Flag on but no profile yet → fall back so the user still gets recommendations.
            log.debug("RECO_SIGNALS user={} profile-source on but no profile row; using legacy fallback", userId);
        }
        return fromWorkflow(userId);
    }

    // ── Authoritative source: the canonical Candidate Profile ────────────────────────────

    private CandidateMatchSignals fromProfile(CandidateProfile p) {
        List<String> skills = JsonLists.toList(p.getSkillsJson());
        List<String> targetRoles = JsonLists.toList(p.getTargetRolesJson());
        List<String> countries = JsonLists.toList(p.getPreferredCountriesJson());
        List<String> cities = JsonLists.toList(p.getPreferredCitiesJson());
        List<String> workModes = JsonLists.toList(p.getWorkModesJson());
        List<String> excluded = JsonLists.toList(p.getExcludedRolesJson());

        JobScoring.PreferenceContext prefs = new JobScoring.PreferenceContext(
                countries, cities,
                workModes.contains("Remote"), workModes.contains("Hybrid"), workModes.contains("Onsite"),
                Boolean.TRUE.equals(p.getVisaRequired()),
                p.getSalaryMin(), p.getSalaryTarget());

        log.debug("RECO_SIGNALS user={} source=PROFILE skills={} roles={} excluded={}",
                p.getUserId(), skills.size(), targetRoles.size(), excluded.size());
        return new CandidateMatchSignals(
                skills,
                String.join(" ", targetRoles),   // combined role string for role-similarity scoring
                union(countries, cities),         // location signal for ctx.targetLocations
                p.getYearsExperience(),
                null,                             // ATS score is workflow-only; not a scoreV2 input
                prefs, excluded, p.getResumeId(), "PROFILE");
    }

    // ── Legacy fallback: latest workflow run + live preferences (pre-Phase-1 behavior) ───

    private Optional<CandidateMatchSignals> fromWorkflow(UUID userId) {
        WorkflowRun latest = runs.findTop20ByUserIdOrderByCreatedAtDesc(userId).stream()
                .findFirst().orElse(null);
        if (latest == null) return Optional.empty();

        Map<String, Object> state = parseState(latest);
        List<String> skills = stringList(state.get("extracted_skills"));
        List<String> targetLocations = stringList(state.get("target_locations"));
        UUID resumeId = parseUuid(state.get("resume_id"));

        var prefDto = preferences.get(userId);
        JobScoring.PreferenceContext prefs = prefDto.toScoringContext();
        String targetRole = combineRole(latest.getTargetRole(), prefDto.preferredRolesOrEmpty());
        Integer yearsExp = intOrNull(asMap(state.get("candidate_profile")).get("years_experience"));

        return Optional.of(new CandidateMatchSignals(
                skills, targetRole, targetLocations, yearsExp, latest.getAtsScore(),
                prefs, prefDto.excludedRolesOrEmpty(), resumeId, "WORKFLOW"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────

    private static List<String> union(List<String> a, List<String> b) {
        Set<String> seen = new LinkedHashSet<>();
        if (a != null) a.forEach(s -> { if (s != null && !s.isBlank()) seen.add(s.trim()); });
        if (b != null) b.forEach(s -> { if (s != null && !s.isBlank()) seen.add(s.trim()); });
        return new ArrayList<>(seen);
    }

    /** Merge the workflow target role with the user's preferred roles into one role string. */
    private static String combineRole(String targetRole, List<String> preferredRoles) {
        StringBuilder sb = new StringBuilder();
        if (targetRole != null && !targetRole.isBlank()) sb.append(targetRole);
        if (preferredRoles != null) {
            for (String r : preferredRoles) {
                if (r != null && !r.isBlank()) sb.append(' ').append(r);
            }
        }
        return sb.toString().trim();
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

    private Integer intOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static UUID parseUuid(Object value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
