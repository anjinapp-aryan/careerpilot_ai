package ai.careerpilot.discovery.relevance;

import ai.careerpilot.domain.Job;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 3B.1 — composes the four eligibility checks (role, experience, skills, domain) into a
 * single verdict. Short-circuit evaluation order matches the spec's listing: role, experience,
 * skills, domain — the first failing check is reported as the rejection reason.
 *
 * <p>Skill eligibility itself has no independent pass/fail threshold in the spec beyond feeding
 * {@link CareerRelevanceScore}'s 30% weight, so {@code REJECTED_SKILLS} fires only on a hard-zero
 * overlap (no shared skill family at all) — anything else is scored, not gated, on skills.
 */
@Component
public class JobEligibilityEngine {

    /** Below this overlap percent, a job is treated as having no meaningful skill relevance. */
    private static final int MIN_SKILL_OVERLAP_PERCENT = 1;

    public enum Verdict { ELIGIBLE, REJECTED_ROLE, REJECTED_EXPERIENCE, REJECTED_SKILLS, REJECTED_DOMAIN }

    public record Result(Verdict verdict, boolean roleMatch, int roleSimilarity,
                         boolean experienceMatch, int skillOverlap, boolean domainMatch) {
        public boolean eligible() { return verdict == Verdict.ELIGIBLE; }
    }

    private final RoleFamilyService roleFamilyService;
    private final ExperienceEligibilityService experienceService;
    private final SkillOverlapService skillOverlapService;
    private final DomainPreferenceService domainService;

    public JobEligibilityEngine(RoleFamilyService roleFamilyService,
                                ExperienceEligibilityService experienceService,
                                SkillOverlapService skillOverlapService,
                                DomainPreferenceService domainService) {
        this.roleFamilyService = roleFamilyService;
        this.experienceService = experienceService;
        this.skillOverlapService = skillOverlapService;
        this.domainService = domainService;
    }

    public Result evaluate(Job job, RelevanceCandidateContext ctx) {
        var role = roleFamilyService.evaluate(ctx.targetRole(), job.getTitle(), job.getDescription());
        boolean experienceMatch = experienceService.isEligible(ctx.yearsExperience(), job.getRequiredExperience());
        int skillOverlap = skillOverlapService.overlapPercent(
                ctx.skills() == null ? List.of() : ctx.skills(), job.getSkills());
        boolean domainMatch = domainService.fitsPreferredDomains(job, ctx.preferredDomains());

        Verdict verdict;
        if (!role.eligible()) {
            verdict = Verdict.REJECTED_ROLE;
        } else if (!experienceMatch) {
            verdict = Verdict.REJECTED_EXPERIENCE;
        } else if (skillOverlap < MIN_SKILL_OVERLAP_PERCENT) {
            verdict = Verdict.REJECTED_SKILLS;
        } else if (!domainMatch) {
            verdict = Verdict.REJECTED_DOMAIN;
        } else {
            verdict = Verdict.ELIGIBLE;
        }

        return new Result(verdict, role.eligible(), role.similarity(), experienceMatch, skillOverlap, domainMatch);
    }
}
