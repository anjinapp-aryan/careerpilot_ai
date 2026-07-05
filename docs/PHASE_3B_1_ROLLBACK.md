# Phase 3B.1 — Rollback Playbook

Ships dark behind four independent flags, all defaulting `false`. No migration was applied (none
was needed), so rollback here is purely a flag operation — see the project's general
[ROLLBACK.md](ROLLBACK.md) for the principles this follows.

## The four flags

| Flag | Default | Effect when `false` |
|---|---|---|
| `career.relevance.enabled` | `false` | **Master switch.** `CareerRelevanceEvaluator.isEnabled()` returns false; `JobService.applyCareerRelevance` short-circuits to a no-op regardless of the other two flags. `GET /api/jobs/{id}/relevance` still 404s independently via its own flag. |
| `career.domestic.filter.enabled` | `false` | Domestic tab is unfiltered by relevance (legacy country-only behavior), even if the master flag is on. |
| `career.international.filter.enabled` | `false` | Same, for International. |
| `career.explainability.enabled` | `false` | `GET /api/jobs/{id}/relevance` returns 404 for everyone. |

## Recommended canary sequence (future, not part of this delivery)

1. Flip `career.relevance.enabled=true` alone — no visible effect yet (both scope flags still
   off), used only to smoke-test `CareerRelevanceEvaluator` wiring in a real environment via the
   explainability endpoint (flip that on too, temporarily, for a handful of test users).
2. Flip `career.domestic.filter.enabled=true` — verify the Domestic tab for a known senior-tech
   test account no longer shows off-topic roles, and that `relevanceScore`/`matchStrength` in the
   explain endpoint look sane for a sample of jobs.
3. Flip `career.international.filter.enabled=true` — same verification for International.
4. Flip `career.explainability.enabled=true` for all users once the UI (a future phase) is ready
   to consume it.

Each step is independently reversible — flip that one flag back to `false` and the prior behavior
returns immediately, with no data to clean up (this phase writes nothing new to the database).

## Rollback = flag off, instantly

- **No rows to delete.** Nothing in this phase persists anything — `CareerRelevanceScore`/
  `CareerRelevanceExplanation` are computed on the fly, every request, from existing data.
- **No cache to invalidate.** No caching layer was introduced.
- **No migration to reverse.** None was written or applied.

## Verifying a rollback

1. `GET /api/jobs/discovered?scope=domestic&country=<home>` — with all flags off, response is
   byte-identical to pre-Phase-3B.1 behavior (verified by `JobServiceCareerRelevanceTest`'s
   `domesticFeedIsUnfilteredWhenScopeFlagOff` / `feedIsUnfilteredWhenMasterFlagOff` cases).
2. `GET /api/jobs/{id}/relevance` — with `career.explainability.enabled=false`, returns 404.
3. `GET /api/jobs/pool` (Browse) — always unfiltered, in every flag combination; this phase never
   reads or writes any Browse-related code path.

## What this phase does NOT touch, so nothing else needs rolling back

Phase 2E (execution engine), Phase 3A (workflow engine), the recommendation engine
(`RecommendationController`/`JobRecommendationService`/`JobMatchingService`), and
`JobScoring`/`JobTaxonomy`'s existing weights and family sets are all read-only dependencies of
this phase — none were modified, so none carry any Phase 3B.1 rollback burden.
