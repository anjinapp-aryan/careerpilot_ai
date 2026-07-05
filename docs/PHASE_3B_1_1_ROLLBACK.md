# Phase 3B.1.1 — Rollback Playbook

Ships dark behind one new flag, `career.soft-thresholds.enabled`, defaulting `false`. No migration
was applied (none was needed). This extends [PHASE_3B_1_ROLLBACK.md](PHASE_3B_1_ROLLBACK.md) —
that document's four flags are unchanged and still govern the master switch, per-scope filters,
and explainability; this document covers only the one new flag layered on top.

## The new flag

| Flag | Default | Effect when `false` |
|---|---|---|
| `career.soft-thresholds.enabled` | `false` | `CareerThresholdPolicy.isVisibleForScope` returns exactly `score >= 70` (the Phase 3B.1 hard cutoff), regardless of the configured per-feed threshold values. `isVisibleForRecommended`/`isVisibleForBrowse` are unaffected either way (not wired into any live filter path). |

## Rollback = flag off, instantly

- **No rows to delete, no cache to invalidate, no migration to reverse** — same as Phase 3B.1;
  this phase adds zero persisted state.
- Flipping `career.soft-thresholds.enabled` back to `false` makes `JobService.discovered`'s
  Domestic/International filtering byte-identical to Phase 3B.1's behavior, because the false
  branch of `isVisibleForScope` is the literal `CareerRelevanceScore.FEED_THRESHOLD` constant, not
  a re-derived value.

## Verifying a rollback

1. `GET /api/jobs/discovered?scope=domestic` with `career.soft-thresholds.enabled=false` — a job
   scoring 65 does **not** appear (verified by
   `JobServiceCareerRelevanceTest.weakScoreIsHiddenUnderLegacyHardCutoff`).
2. Same request with the flag `true` — the same score-65 job **does** appear (verified by
   `JobServiceCareerRelevanceTest.weakScoreIsVisibleWhenSoftThresholdsEnabled`), because the default
   `career.relevance.thresholds.domestic` is `60`.
3. `GET /api/jobs/pool` (Browse) — always unfiltered, unaffected by this flag in any state.

## Recommended canary sequence (future, not part of this delivery)

1. Flip `career.soft-thresholds.enabled=true` alone, with the existing Phase 3B.1 flags already at
   whatever state they're in for Domestic/International — this only changes the *cutoff*, not
   whether filtering happens at all.
2. Monitor for previously-hidden jobs (scores 60-69) reappearing in Domestic/International feeds;
   confirm `matchStrength`/`relevanceScore` in any future explainability surface reads `WEAK` for
   those, not silently `GOOD`.
3. Tune `career.relevance.thresholds.domestic`/`.international` per observed feed quality — each is
   independently adjustable without touching the flag.

## What this phase does NOT touch, so nothing else needs rolling back

Phase 2E, Phase 3A, the recommendation engine (`RecommendationController`/
`JobRecommendationService`/`JobMatchingService`), and Phase 3B.1's `CareerRelevanceScore`/
`CareerRelevanceExplanation`/`JobRelevanceController` are all unmodified — `recommendedThreshold()`
exists on `CareerThresholdPolicy` but is not called by any of them.
