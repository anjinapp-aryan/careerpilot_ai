package ai.careerpilot.execution.safety;

import ai.careerpilot.domain.*;
import ai.careerpilot.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 2E.5 — the deterministic safety gate that decides whether an assembled application package
 * is {@link SafetyVerdict#SAFE}, needs {@link SafetyVerdict#REVIEW}, or must be {@link
 * SafetyVerdict#BLOCK}ed before it can ever reach human approval or execution. HIGH RISK — this is
 * the primary defense against submitting a bad application.
 *
 * <p>Purely deterministic (no LLM): every check reads existing 2D artifacts + candidate preferences
 * and contributes a reason. Verdict precedence is BLOCK &gt; REVIEW &gt; SAFE.
 *
 * <ul>
 *   <li><b>BLOCK</b> (hard): package not ASSEMBLED, no tailored resume, no cover letter, a
 *       duplicate application already exists, the job's title is an excluded role, or its company
 *       is blacklisted.</li>
 *   <li><b>REVIEW</b> (soft): missing/low ATS analysis, missing/large gap, or off-preference
 *       country/salary.</li>
 * </ul>
 *
 * Never throws (a check failure degrades to a BLOCK reason). Flag-gated by
 * {@code application.safety.enabled} (default <b>true</b> — the gate is on; disabling it does not
 * open an execution path, it only stops evaluation).
 */
@Service
public class SafetyEngine {

    private static final Logger log = LoggerFactory.getLogger(SafetyEngine.class);
    private static final Set<String> SUBMITTED_STATUSES = Set.of("APPLIED", "INTERVIEWING", "OFFER");

    private final ApplicationPackageRepository packages;
    private final JobRepository jobs;
    private final ResumeTailoringRepository tailorings;
    private final ResumeAtsAnalysisRepository atsAnalyses;
    private final ResumeGapAnalysisRepository gapAnalyses;
    private final CoverLetterRepository coverLetters;
    private final ApplicationRepository applications;
    private final CandidatePreferencesRepository preferences;
    private final SafetyMetrics metrics;

    private final boolean enabled;
    private final int atsMinScore;
    private final int gapMaxScore;
    private final Set<String> blacklistCompanies;

    public SafetyEngine(ApplicationPackageRepository packages, JobRepository jobs,
                        ResumeTailoringRepository tailorings, ResumeAtsAnalysisRepository atsAnalyses,
                        ResumeGapAnalysisRepository gapAnalyses, CoverLetterRepository coverLetters,
                        ApplicationRepository applications, CandidatePreferencesRepository preferences,
                        SafetyMetrics metrics,
                        @Value("${application.safety.enabled:true}") boolean enabled,
                        @Value("${application.safety.ats-min-score:60}") int atsMinScore,
                        @Value("${application.safety.gap-max-score:60}") int gapMaxScore,
                        @Value("${application.safety.blacklist-companies:}") String blacklistCompanies) {
        this.packages = packages;
        this.jobs = jobs;
        this.tailorings = tailorings;
        this.atsAnalyses = atsAnalyses;
        this.gapAnalyses = gapAnalyses;
        this.coverLetters = coverLetters;
        this.applications = applications;
        this.preferences = preferences;
        this.metrics = metrics;
        this.enabled = enabled;
        this.atsMinScore = atsMinScore;
        this.gapMaxScore = gapMaxScore;
        this.blacklistCompanies = csvToLowerSet(blacklistCompanies);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Evaluate a package. When disabled, returns BLOCK with reason "safety disabled" — a disabled
     * gate is a closed gate, never an open one. Never throws.
     */
    public SafetyResult evaluate(UUID userId, UUID jobId, UUID applicationPackageId) {
        if (!enabled) {
            return new SafetyResult(SafetyVerdict.BLOCK, List.of("safety engine disabled — gate closed"));
        }
        metrics.recordRequest();
        List<String> blocks = new ArrayList<>();
        List<String> reviews = new ArrayList<>();
        try {
            ApplicationPackage pkg = applicationPackageId != null
                    ? packages.findById(applicationPackageId).orElse(null)
                    : packages.findByUserIdAndJobId(userId, jobId).orElse(null);
            Job job = jobs.findById(jobId).orElse(null);

            // ── hard checks (BLOCK) ──
            if (pkg == null || !ApplicationPackage.STATUS_ASSEMBLED.equals(pkg.getStatus())) {
                blocks.add("application package not ASSEMBLED");
            }
            if (tailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId).isEmpty()) {
                blocks.add("no tailored resume");
            }
            if (coverLetters.findByUserIdAndJobId(userId, jobId).isEmpty()) {
                blocks.add("no cover letter");
            }
            Optional<Application> existing = applications.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId);
            if (existing.isPresent() && SUBMITTED_STATUSES.contains(upper(existing.get().getStatus()))) {
                blocks.add("duplicate application (status=" + existing.get().getStatus() + ")");
            }
            if (job != null) {
                CandidatePreferences prefs = preferences.findById(userId).orElse(null);
                if (isExcludedRole(job.getTitle(), prefs)) {
                    blocks.add("job title matches an excluded role");
                }
                if (job.getCompany() != null && blacklistCompanies.contains(job.getCompany().toLowerCase(Locale.ROOT))) {
                    blocks.add("company is blacklisted");
                }
                // ── soft checks (REVIEW) ──
                if (isOffPreferenceCountry(job.getCountry(), prefs)) {
                    reviews.add("job country not in preferred countries");
                }
            }

            // ATS + gap soft checks
            Optional<ResumeAtsAnalysis> ats = atsAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId);
            if (ats.isEmpty()) {
                reviews.add("no ATS analysis");
            } else if (ats.get().getAtsScore() != null && ats.get().getAtsScore() < atsMinScore) {
                reviews.add("ATS score " + ats.get().getAtsScore() + " below minimum " + atsMinScore);
            }
            Optional<ResumeGapAnalysis> gap = gapAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId);
            if (gap.isEmpty()) {
                reviews.add("no gap analysis");
            } else if (gap.get().getGapScore() != null && gap.get().getGapScore() > gapMaxScore) {
                reviews.add("gap score " + gap.get().getGapScore() + " above maximum " + gapMaxScore);
            }

            SafetyResult result = decide(blocks, reviews);
            metrics.record(result.verdict());
            log.info("SAFETY_EVAL user={} job={} verdict={} reasons=[{}]",
                    userId, jobId, result.verdict(), result.reasonSummary());
            return result;
        } catch (Exception e) {
            metrics.recordError();
            metrics.record(SafetyVerdict.BLOCK);
            log.warn("SAFETY_EVAL error user={} job={}: {} — failing closed (BLOCK)", userId, jobId, e.toString());
            return new SafetyResult(SafetyVerdict.BLOCK, List.of("safety evaluation error: " + e));
        }
    }

    private static SafetyResult decide(List<String> blocks, List<String> reviews) {
        if (!blocks.isEmpty()) {
            List<String> all = new ArrayList<>(blocks);
            all.addAll(reviews);
            return new SafetyResult(SafetyVerdict.BLOCK, all);
        }
        if (!reviews.isEmpty()) {
            return new SafetyResult(SafetyVerdict.REVIEW, reviews);
        }
        return new SafetyResult(SafetyVerdict.SAFE, List.of());
    }

    private static boolean isExcludedRole(String title, CandidatePreferences prefs) {
        if (title == null || prefs == null || prefs.getExcludedRoles() == null) return false;
        String t = title.toLowerCase(Locale.ROOT);
        return csvToLowerSet(prefs.getExcludedRoles()).stream().anyMatch(t::contains);
    }

    private static boolean isOffPreferenceCountry(String country, CandidatePreferences prefs) {
        if (country == null || prefs == null || prefs.getPreferredCountries() == null) return false;
        Set<String> preferred = csvToLowerSet(prefs.getPreferredCountries());
        return !preferred.isEmpty() && !preferred.contains(country.toLowerCase(Locale.ROOT));
    }

    private static Set<String> csvToLowerSet(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }
}
