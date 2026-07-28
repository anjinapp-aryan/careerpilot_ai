package ai.careerpilot.jobdiscovery.international;

import ai.careerpilot.domain.Job;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * International Job Discovery Engine, Phase 1 — the single seam callers use for seniority/title
 * eligibility. Two independent flags: {@code career.international.seniority-filter.enabled} is
 * the master switch (explicit-reject titles + seniority level); {@code
 * career.international.title-allowlist.enabled} is a separately-toggleable, more aggressive
 * sub-gate requiring the job to match one of the 27 allow-listed titles. With the master flag
 * off, {@link #isEligible} always returns {@code true} — a byte-identical no-op, safe to wire
 * into the existing filter chain unconditionally (same pattern as {@code hardPreferenceEnabled}
 * in {@code JobMatchingService}).
 */
@Component
public class InternationalEligibilityFilter {

    private final SeniorityLevelClassifier seniority;
    private final InternationalRoleTaxonomy roleTaxonomy;
    private final boolean enabled;
    private final boolean requireAllowedTitle;

    public InternationalEligibilityFilter(SeniorityLevelClassifier seniority,
                                           InternationalRoleTaxonomy roleTaxonomy,
                                           @Value("${career.international.seniority-filter.enabled:false}") boolean enabled,
                                           @Value("${career.international.title-allowlist.enabled:false}") boolean requireAllowedTitle) {
        this.seniority = seniority;
        this.roleTaxonomy = roleTaxonomy;
        this.enabled = enabled;
        this.requireAllowedTitle = requireAllowedTitle;
    }

    public boolean isEligible(Job job) {
        if (!enabled) return true;
        if (seniority.isExplicitlyRejectedTitle(job)) return false;
        if (!seniority.isEligibleSeniority(job)) return false;
        if (requireAllowedTitle && !roleTaxonomy.matchesAllowedTitle(job)) return false;
        return true;
    }
}
