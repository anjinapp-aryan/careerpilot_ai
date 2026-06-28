package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.Job;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Shared, deterministic (AI-free) skill/role/location scoring used by both the on-the-fly
 * {@code JobRecommendationService} and the persisted {@code JobMatchingService}, so the two
 * can never diverge. Also exposes skill extraction for the ingest normalizer.
 *
 * <p>The vocabulary mirrors the lowercase canonical style the resume_intelligence agent
 * emits — it exists only to detect skill mentions in free-text descriptions, it is not a
 * source of truth and is never persisted as such.
 */
@Component
public class JobScoring {

    private final JobTaxonomy taxonomy;

    public JobScoring(JobTaxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    public static final List<String> SKILL_VOCABULARY = List.of(
            "java", "spring boot", "spring", "kotlin", "python", "javascript", "typescript",
            "react", "node", "node.js", "go", "golang", "rust", "c++", "c#", ".net",
            "kubernetes", "docker", "aws", "azure", "gcp", "terraform", "ansible",
            "kafka", "rabbitmq", "redis", "postgresql", "mysql", "mongodb", "elasticsearch",
            "graphql", "rest", "microservices", "ci/cd", "jenkins", "git", "linux",
            "machine learning", "data engineering", "sql", "nosql", "fastapi", "django",
            "flask", "spring cloud", "hibernate", "jpa", "vue", "angular", "next.js",
            "ci", "cd", "agile", "scrum", "leadership", "mentoring", "system design");

    /** Result of scoring one job against a candidate. */
    public record ScoreResult(int matchScore, List<String> matchedSkills, List<String> missingSkills) {}

    // ── v2: weighted 6-factor scoring with per-factor breakdown + confidence ─────────

    /**
     * Per-factor 0-100 sub-scores behind a v2 match score. Serialized to JSON for the UI.
     * Weights: skills 35, experience 20, role 15, location 10, salary 10, visa 5, workMode 5.
     */
    public record ScoreBreakdown(int skills, int experience, int role, int location,
                                 int salary, int visa, int workMode) {}

    /**
     * Full v2 result: total + matched/missing skills + breakdown + confidence (HIGH|MEDIUM|LOW).
     * {@code matchedSkillFamilyCount}/{@code matchedRoleCount} back the Recommended quality gate
     * (>= 3 skill families AND >= 1 role family) without persisting extra columns.
     */
    public record ScoreResultV2(int matchScore, List<String> matchedSkills, List<String> missingSkills,
                                ScoreBreakdown breakdown, String confidence,
                                int matchedSkillFamilyCount, int matchedRoleCount) {}

    /** Candidate signals derived from the latest workflow run. */
    public record CandidateContext(List<String> skills, String targetRole, List<String> targetLocations,
                                   Integer yearsExperience, Integer atsScore) {}

    /** Persisted candidate preferences relevant to scoring (location/salary/remote/visa). */
    public record PreferenceContext(List<String> preferredCountries, List<String> preferredCities,
                                    boolean remote, boolean hybrid, boolean onsite,
                                    boolean visaRequired,
                                    java.math.BigDecimal salaryMin, java.math.BigDecimal salaryMax) {
        public static PreferenceContext empty() {
            return new PreferenceContext(List.of(), List.of(), false, false, false, false, null, null);
        }
        public boolean anyRemotePref() { return remote || hybrid || onsite; }
        public boolean anyLocationPref() {
            return (preferredCountries != null && !preferredCountries.isEmpty())
                    || (preferredCities != null && !preferredCities.isEmpty());
        }
    }

    /** Generic tokens stripped before computing role similarity — they carry no domain signal. */
    private static final Set<String> ROLE_STOPWORDS = Set.of(
            "developer", "engineer", "programmer", "senior", "junior", "lead", "principal", "staff",
            "sr", "jr", "mid", "level", "intern", "trainee", "the", "a", "an", "and", "or", "of",
            "for", "with", "to", "in", "i", "ii", "iii", "iv", "m", "w", "d", "f", "x", "remote",
            "hybrid", "onsite", "fulltime", "part", "time", "contract", "freelance");

    /**
     * Minimum denominator for the skill ratio so a JD that only names one or two skills the
     * candidate happens to have can't reach 100% — that loophole let sparse, off-target JDs
     * (e.g. a marketing post mentioning "SQL") look like perfect skill matches.
     */
    private static final int SKILL_DENOM_FLOOR = 3;

    /**
     * Weighted score: skills 40, role 25, experience 20, location 10, salary 3, visa/workMode 1 each.
     * Skills and role are computed over normalized <i>families</i> (so spelling variants and related
     * roles match), and negative signals score low rather than the old neutral 50 — that 50 default
     * was the main reason irrelevant jobs floated to high scores. The breakdown record keeps its
     * 7-field shape (API contract) even though salary/visa/workMode now carry little weight.
     */
    public ScoreResultV2 scoreV2(Job job, CandidateContext ctx, PreferenceContext prefs) {
        PreferenceContext p = prefs == null ? PreferenceContext.empty() : prefs;
        String haystack = ((job.getTitle() == null ? "" : job.getTitle()) + " "
                + (job.getDescription() == null ? "" : job.getDescription())).toLowerCase();

        int signals = 0;

        // ── Skills (40%) — normalized skill families, with a denominator floor ──────────
        Set<String> candFamilies = taxonomy.skillFamilies(ctx.skills());
        Set<String> jobSkillFamilies = new HashSet<>(taxonomy.skillFamiliesInText(haystack));
        if (job.getSkills() != null) {
            jobSkillFamilies.addAll(taxonomy.skillFamilies(
                    Arrays.asList(job.getSkills().toLowerCase().split("\\s*,\\s*"))));
        }
        Set<String> matchedFamilies = new HashSet<>(candFamilies);
        matchedFamilies.retainAll(jobSkillFamilies);

        // Display lists (concrete vocabulary terms) keyed off family membership.
        List<String> mentionedSkills = SKILL_VOCABULARY.stream().filter(haystack::contains).toList();
        List<String> matchedSkills = mentionedSkills.stream()
                .filter(s -> candFamilies.contains(taxonomy.skillFamily(s))).distinct().toList();
        List<String> missingSkills = mentionedSkills.stream()
                .filter(s -> !candFamilies.contains(taxonomy.skillFamily(s))).distinct().toList();

        int skills;
        if (candFamilies.isEmpty()) {
            skills = 50;                              // no candidate skills on file → can't assess
        } else if (jobSkillFamilies.isEmpty()) {
            skills = 25;                              // JD names no recognizable tech skills → weak fit
        } else {
            int denom = Math.max(jobSkillFamilies.size(), SKILL_DENOM_FLOOR);
            skills = Math.min(100, matchedFamilies.size() * 100 / denom);
            signals++;
        }

        // ── Role (25%) — coarse role-family overlap via the taxonomy ────────────────────
        Set<String> candRoles = taxonomy.roleFamilies(ctx.targetRole());
        Set<String> jobRoles = taxonomy.roleFamilies(job.getTitle());
        int matchedRoleCount;
        int role;
        if (candRoles.isEmpty() || jobRoles.isEmpty()) {
            matchedRoleCount = 0;
            role = 40;                               // unknown on either side → mild, not neutral-50
        } else {
            Set<String> roleOverlap = new HashSet<>(candRoles);
            roleOverlap.retainAll(jobRoles);
            matchedRoleCount = roleOverlap.size();
            int denom = Math.min(candRoles.size(), jobRoles.size());
            role = matchedRoleCount == 0 ? 10 : Math.min(100, roleOverlap.size() * 100 / denom);
            signals++;
        }

        // ── Experience (20%) — seniority-aware; de-prioritize junior roles for seniors ──
        int experience = experienceScore(job, ctx);
        if (taxonomy.seniorityLevel(job.getTitle()) != JobTaxonomy.SENIORITY_UNKNOWN
                || (job.getRequiredExperience() != null && ctx.yearsExperience() != null)) {
            signals++;
        }

        // ── Location (10%) ──────────────────────────────────────────────────────────────
        int location;
        boolean jobRemote = "REMOTE".equals(job.getRemoteType()) || Boolean.TRUE.equals(job.getRemote());
        if (p.anyLocationPref()) {
            location = locationMatches(job, p) ? 100 : (jobRemote ? 80 : 25);
            signals++;
        } else if (ctx.targetLocations() != null && !ctx.targetLocations().isEmpty()) {
            String jobLoc = job.getLocation() == null ? "" : job.getLocation().toLowerCase();
            location = ctx.targetLocations().stream().anyMatch(l -> jobLoc.contains(l.toLowerCase()))
                    ? 100 : (jobRemote ? 80 : 30);
            signals++;
        } else {
            location = jobRemote ? 100 : 50;
        }

        // ── Salary (3%) ─────────────────────────────────────────────────────────────────
        int salary = 50;
        boolean salSignal = (job.getSalaryMin() != null || job.getSalaryMax() != null)
                && (p.salaryMin() != null || p.salaryMax() != null);
        if (salSignal) {
            salary = salaryScore(job, p);
            signals++;
        }

        // ── Visa sponsorship (1%) ───────────────────────────────────────────────────────
        int visa = 50;
        if (p.visaRequired()) {
            visa = Boolean.TRUE.equals(job.getSponsorshipAvailable()) ? 100
                    : Boolean.FALSE.equals(job.getSponsorshipAvailable()) ? 0 : 40;
            signals++;
        } else if (job.getSponsorshipAvailable() != null) {
            visa = 100;
        }

        // ── Work-mode preference (1%) ───────────────────────────────────────────────────
        int workMode = 50;
        boolean workModeSignal = p.anyRemotePref() && job.getRemoteType() != null;
        if (workModeSignal) {
            workMode = remotePrefMatches(job.getRemoteType(), p) ? 100 : 30;
            signals++;
        }

        int total = Math.round(skills * 0.40f + role * 0.25f + experience * 0.20f + location * 0.10f
                + salary * 0.03f + visa * 0.01f + workMode * 0.01f);
        total = Math.min(100, Math.max(0, total));

        String confidence = signals >= 4 ? "HIGH" : signals >= 2 ? "MEDIUM" : "LOW";
        return new ScoreResultV2(total, matchedSkills, missingSkills,
                new ScoreBreakdown(skills, experience, role, location, salary, visa, workMode), confidence,
                matchedFamilies.size(), matchedRoleCount);
    }

    /**
     * Seniority-aware experience fit. A 12-yr candidate matching a Senior/Lead/Architect role scores
     * high; the same candidate against an Intern/Junior/Graduate role is strongly de-prioritized
     * (the old {@code have >= req ? 100} logic rewarded exactly these mismatches). Falls back to the
     * years-vs-required comparison, then to a neutral 50 only when no signal exists at all.
     */
    private int experienceScore(Job job, CandidateContext ctx) {
        int jobLevel = taxonomy.seniorityLevel(job.getTitle());
        Integer have = ctx.yearsExperience();
        int candLevel = taxonomy.seniorityFromYears(have);

        if (jobLevel != JobTaxonomy.SENIORITY_UNKNOWN && candLevel != JobTaxonomy.SENIORITY_UNKNOWN) {
            int gap = candLevel - jobLevel;             // >0 = candidate over-levelled for the role
            if (gap == 0) return 100;
            if (gap == 1) return 90;                    // one rung senior → still a fine fit
            if (gap >= 2) return Math.max(10, 100 - gap * 30); // over-qualified (e.g. senior → junior)
            return Math.max(20, 100 + gap * 20);        // under-levelled (job wants more senior)
        }
        if (job.getRequiredExperience() != null && have != null) {
            int req = job.getRequiredExperience();
            if (have >= req) {
                // Meets the bar, but a senior candidate against a near-zero requirement is a junior role.
                return (req <= 1 && have >= 8) ? 45 : 100;
            }
            return Math.max(0, 100 - (req - have) * 15);
        }
        return 50;                                       // genuinely no signal
    }

    /**
     * Role similarity 0-100, or -1 when there is no signal (can't compare). Blends the candidate's
     * target-role tokens + skills against the job's title tokens + skills using overlap-coefficient.
     * Generic tokens (developer/engineer/senior/…) are stripped so "PHP Developer" vs "Java Architect"
     * scores ~0 (→ rejected by the relevance gate) while "Java Developer" vs "Java Architect" stays high.
     */
    public int roleSimilarity(Job job, List<String> candidateSkills, String targetRole) {
        Set<String> candTerms = new java.util.HashSet<>(roleTokens(targetRole));
        if (candidateSkills != null) candidateSkills.forEach(s -> { if (s != null) candTerms.add(s.toLowerCase().trim()); });

        Set<String> jobTerms = new java.util.HashSet<>(roleTokens(job.getTitle()));
        jobTerms.addAll(extractSkills((job.getTitle() == null ? "" : job.getTitle()) + " "
                + (job.getDescription() == null ? "" : job.getDescription())));
        if (job.getSkills() != null) {
            Arrays.stream(job.getSkills().split(",")).map(String::trim).map(String::toLowerCase)
                    .filter(s -> !s.isEmpty()).forEach(jobTerms::add);
        }

        candTerms.remove("");
        jobTerms.remove("");
        if (candTerms.isEmpty() || jobTerms.isEmpty()) return -1; // no signal → don't reject

        long overlap = candTerms.stream().filter(jobTerms::contains).count();
        int denom = Math.min(candTerms.size(), jobTerms.size());
        return (int) Math.min(100, Math.round(100.0 * overlap / denom));
    }

    /** Meaningful (non-generic) lowercase tokens from a role/title string. */
    private static List<String> roleTokens(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.toLowerCase().split("[^a-z0-9+#.]+"))
                .map(String::trim)
                .filter(t -> t.length() > 1 && !ROLE_STOPWORDS.contains(t))
                .distinct()
                .toList();
    }

    private boolean locationMatches(Job job, PreferenceContext p) {
        String country = job.getCountry() == null ? "" : job.getCountry().toLowerCase();
        String city = job.getCity() == null ? "" : job.getCity().toLowerCase();
        String loc = job.getLocation() == null ? "" : job.getLocation().toLowerCase();
        boolean countryHit = p.preferredCountries().stream().map(String::toLowerCase)
                .anyMatch(c -> !c.isBlank() && (country.equals(c) || loc.contains(c)));
        boolean cityHit = p.preferredCities().stream().map(String::toLowerCase)
                .anyMatch(c -> !c.isBlank() && (city.equals(c) || loc.contains(c)));
        return countryHit || cityHit;
    }

    private boolean remotePrefMatches(String remoteType, PreferenceContext p) {
        return switch (remoteType) {
            case "REMOTE" -> p.remote();
            case "HYBRID" -> p.hybrid();
            case "ONSITE" -> p.onsite();
            default -> false;
        };
    }

    private int salaryScore(Job job, PreferenceContext p) {
        java.math.BigDecimal jobMax = job.getSalaryMax() != null ? job.getSalaryMax() : job.getSalaryMin();
        java.math.BigDecimal jobMin = job.getSalaryMin() != null ? job.getSalaryMin() : job.getSalaryMax();
        java.math.BigDecimal want = p.salaryMin() != null ? p.salaryMin() : p.salaryMax();
        if (want == null || jobMax == null) return 50;
        if (jobMax.compareTo(want) >= 0) return 100;          // job ceiling meets expectation
        // job pays less than wanted — scale by how close the top of the band is
        double ratio = jobMax.doubleValue() / Math.max(1.0, want.doubleValue());
        int score = (int) Math.round(Math.max(0, Math.min(1.0, ratio)) * 100);
        return jobMin != null && jobMin.compareTo(want) >= 0 ? Math.max(score, 90) : score;
    }

    /** Detect known skills mentioned anywhere in the given free text. */
    public List<String> extractSkills(String text) {
        if (text == null || text.isBlank()) return List.of();
        String haystack = text.toLowerCase();
        return SKILL_VOCABULARY.stream().filter(haystack::contains).distinct().toList();
    }

    /**
     * Score a job against a candidate. Identical weighting to the original
     * {@code JobRecommendationService} logic: 60% skills, 25% role, 15% location.
     */
    public ScoreResult score(Job job, List<String> candidateSkills, String targetRole, List<String> targetLocations) {
        String haystack = ((job.getTitle() == null ? "" : job.getTitle()) + " "
                + (job.getDescription() == null ? "" : job.getDescription())).toLowerCase();

        Set<String> candidateSkillSet = candidateSkills.stream()
                .filter(Objects::nonNull).map(String::toLowerCase).collect(Collectors.toSet());

        List<String> mentionedSkills = SKILL_VOCABULARY.stream().filter(haystack::contains).toList();
        List<String> matchedSkills = mentionedSkills.stream().filter(candidateSkillSet::contains).toList();
        List<String> missingSkills = mentionedSkills.stream().filter(s -> !candidateSkillSet.contains(s)).toList();

        int skillsScore = mentionedSkills.isEmpty() ? 50 : (matchedSkills.size() * 100 / mentionedSkills.size());

        int roleScore;
        if (targetRole == null || targetRole.isBlank()) {
            roleScore = 50;
        } else {
            String role = targetRole.toLowerCase();
            roleScore = haystack.contains(role) ? 100
                    : Arrays.stream(role.split("\\s+")).anyMatch(haystack::contains) ? 60
                    : 20;
        }

        int locationScore;
        String jobLocation = job.getLocation() == null ? "" : job.getLocation().toLowerCase();
        boolean remoteFlag = Boolean.TRUE.equals(job.getRemote());
        if (remoteFlag || haystack.contains("remote") || jobLocation.contains("remote")) {
            locationScore = 100;
        } else if (targetLocations == null || targetLocations.isEmpty()) {
            locationScore = 50;
        } else {
            locationScore = targetLocations.stream().anyMatch(loc -> jobLocation.contains(loc.toLowerCase())) ? 100 : 30;
        }

        int total = Math.round(skillsScore * 0.6f + roleScore * 0.25f + locationScore * 0.15f);
        return new ScoreResult(Math.min(100, Math.max(0, total)), matchedSkills, missingSkills);
    }
}
