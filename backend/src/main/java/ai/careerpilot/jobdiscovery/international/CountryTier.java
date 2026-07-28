package ai.careerpilot.jobdiscovery.international;

/**
 * International Job Discovery Engine, Phase 1 — the three launch tiers. Later phases can add
 * tiers without touching this enum's existing values (Java enums are additive-safe: a new
 * constant doesn't change the meaning of TIER_1/TIER_2/TIER_3 already stored in
 * {@code supported_countries.tier}).
 */
public enum CountryTier {
    TIER_1,
    TIER_2,
    TIER_3
}
