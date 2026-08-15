package ai.careerpilot.jobdiscovery.international;

/**
 * International Job Discovery Phase 2 — the business search-priority hierarchy, deliberately
 * separate from {@link CountryTier}. {@code tier} already has live meaning elsewhere in this
 * codebase (existing scoring lookups, badge coloring) that doesn't line up with this hierarchy
 * (e.g. Ireland is TIER_2, Luxembourg is TIER_3) — reusing it would silently reinterpret every
 * existing reader. This is a second, additive, nullable classification: "how hard should the
 * platform push this market," never a replacement for technical match score (see {@link
 * ai.careerpilot.jobdiscovery.international.CandidateCountryFitClassifier} for the thing that
 * actually varies per candidate).
 */
public enum SearchPriority {
    PRIMARY,
    PRIMARY_SPECIALIST,
    SECONDARY,
    SELECTIVE
}
