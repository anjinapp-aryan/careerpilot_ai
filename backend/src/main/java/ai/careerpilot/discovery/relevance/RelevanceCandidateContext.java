package ai.careerpilot.discovery.relevance;

import java.util.List;

/**
 * Phase 3B.1 — the candidate signals the eligibility/scoring engine needs, resolved once per
 * request. {@code preferredDomains} is sourced from the canonical {@code CandidateProfile}'s
 * {@code domains}/{@code industries} fields (already populated, previously unconsumed by any
 * matcher — see {@code DomainPreferenceService}).
 */
public record RelevanceCandidateContext(
        String targetRole,
        List<String> skills,
        Integer yearsExperience,
        List<String> preferredDomains) {
}
