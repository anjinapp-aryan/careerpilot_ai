package ai.careerpilot.jobdiscovery.international;

import ai.careerpilot.domain.CountryIntelligence;
import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.JobScoring;
import ai.careerpilot.jobdiscovery.JobTaxonomy;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * International Job Discovery Engine, Phase 1 — the weighted international ranking formula from
 * the program spec: 40% skill match, 15% visa probability, 10% salary, 10% career growth, 10%
 * company stability, 5% remote flexibility, 5% principal-engineer-growth, 5% AI tech stack.
 * Deliberately separate from {@link JobScoring#scoreV2}'s own weights (skills 40/role 25/
 * experience 20/location 10/salary 3/visa 1/workMode 1) — a second, differently-weighted formula
 * for a different purpose (relocation viability, not general job fit), never touching scoreV2's
 * live behavior. All factors deterministic (no LLM), 0-100.
 */
@Component
public class InternationalJobScoring {

    private final JobScoring scoring;
    private final JobTaxonomy taxonomy;

    public InternationalJobScoring(JobScoring scoring, JobTaxonomy taxonomy) {
        this.scoring = scoring;
        this.taxonomy = taxonomy;
    }

    public record InternationalScoreResult(int rankScore, int skillScore, int visaProbabilityScore,
                                            int salaryScore, int careerGrowthScore, int companyStabilityScore,
                                            int remoteFlexibilityScore, int principalEngineerGrowthScore,
                                            int aiTechStackScore) {}

    public InternationalScoreResult score(Job job, String effectiveSkills, JobScoring.CandidateContext ctx,
                                           JobScoring.PreferenceContext prefs, CountryIntelligence intel) {
        int skill = scoring.skillScoreOnly(job, effectiveSkills, ctx);
        int visa = visaProbabilityScore(job, intel);
        int salary = scoring.salaryScore(job, prefs == null ? JobScoring.PreferenceContext.empty() : prefs);
        int careerGrowth = careerGrowthScore(job);
        int companyStability = companyStabilityScore(job);
        int remoteFlexibility = remoteFlexibilityScore(job);
        int principalGrowth = principalEngineerGrowthScore(intel, careerGrowth);
        int aiStack = aiTechStackScore(job, effectiveSkills, intel);

        int rank = Math.round(skill * 0.40f + visa * 0.15f + salary * 0.10f + careerGrowth * 0.10f
                + companyStability * 0.10f + remoteFlexibility * 0.05f + principalGrowth * 0.05f + aiStack * 0.05f);
        rank = Math.min(100, Math.max(0, rank));

        return new InternationalScoreResult(rank, skill, visa, salary, careerGrowth, companyStability,
                remoteFlexibility, principalGrowth, aiStack);
    }

    /** Curated country visa-probability score blended with the job's own sponsorship signal. */
    private int visaProbabilityScore(Job job, CountryIntelligence intel) {
        int base = intel != null ? intel.getVisaProbabilityScore() : 50;
        if (Boolean.TRUE.equals(job.getSponsorshipAvailable())) return Math.min(100, Math.round(base * 1.1f));
        if (Boolean.FALSE.equals(job.getSponsorshipAvailable())) return Math.round(base * 0.5f);
        return base;
    }

    /** Proxy from the job's own seniority level (JobTaxonomy — same taxonomy the rest of the app uses). */
    private int careerGrowthScore(Job job) {
        int level = taxonomy.seniorityLevel(job.getTitle());
        if (level == JobTaxonomy.SENIORITY_PRINCIPAL) return 100;
        if (level == JobTaxonomy.SENIORITY_LEAD) return 85;
        if (level == JobTaxonomy.SENIORITY_SENIOR) return 70;
        return 50;
    }

    /** Proxy from the job's own companySize field (STARTUP/SMB/MID/ENTERPRISE, already populated at ingest). */
    private int companyStabilityScore(Job job) {
        String size = job.getCompanySize();
        if (size == null) return 50;
        return switch (size) {
            case "ENTERPRISE" -> 90;
            case "MID" -> 70;
            case "SMB" -> 55;
            case "STARTUP" -> 40;
            default -> 50;
        };
    }

    private int remoteFlexibilityScore(Job job) {
        String remoteType = job.getRemoteType();
        if (remoteType == null) return 50;
        return switch (remoteType) {
            case "REMOTE" -> 100;
            case "HYBRID" -> 65;
            case "ONSITE" -> 30;
            default -> 50;
        };
    }

    /** Blends the curated country signal with the job-level career-growth proxy (70/30). */
    private int principalEngineerGrowthScore(CountryIntelligence intel, int careerGrowthScore) {
        if (intel == null) return careerGrowthScore;
        return Math.round(intel.getPrincipalEngineerGrowthScore() * 0.7f + careerGrowthScore * 0.3f);
    }

    /**
     * Fraction of the job's skill families that fall in the existing ML role-family trigger-phrase
     * set (reused from {@link JobTaxonomy}, not duplicated), blended with the curated country
     * AI-market signal (50/50) when present.
     */
    private int aiTechStackScore(Job job, String effectiveSkills, CountryIntelligence intel) {
        String haystack = ((job.getTitle() == null ? "" : job.getTitle()) + " "
                + (job.getDescription() == null ? "" : job.getDescription()) + " "
                + (effectiveSkills == null ? "" : effectiveSkills)).toLowerCase();
        Set<String> jobFamilies = taxonomy.roleFamilies(haystack);
        int jobSignal = jobFamilies.contains("ML") ? 100 : 20;
        if (intel == null) return jobSignal;
        return Math.round(jobSignal * 0.5f + intel.getAiMarketScore() * 0.5f);
    }
}
