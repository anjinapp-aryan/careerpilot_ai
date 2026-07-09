# Phase 3B.1.1 — Soft Relevance Threshold Engine

Replaces the flat `score < 70 => reject` cutoff on Domestic/International with configurable,
per-feed soft bands, so a highly-relevant-but-not-quite-70 role (e.g. score 68) is no longer
invisible. Ships **fully dark and additive** — behavior is byte-identical to Phase 3B.1 until a new
flag is explicitly flipped.

## Problem

Phase 3B.1's hard `>= 70` gate meant a Java Architect role scoring 68 vanished from the feed
entirely, even though 68 is meaningfully different from, say, 20. The fix is graded visibility
(EXCELLENT/STRONG/GOOD/WEAK/HIDDEN) with a configurable per-feed cutoff, not a single number.

## What was built

| Class | Responsibility |
|---|---|
| `CareerMatchStrength` | New enum: `EXCELLENT` (90-100), `STRONG` (80-89), `GOOD` (70-79), `WEAK` (60-69), `HIDDEN` (<60) |
| `CareerThresholdPolicy` | New `@Component`. Per-feed threshold accessors (`recommendedThreshold()`, `domesticThreshold()`, `internationalThreshold()`, `browseThreshold()`), gated by `career.soft-thresholds.enabled` |
| `CareerRelevanceResult` | New record: `{score, strength, visible, reasons}` — the Step-explainability shape this phase's spec asked for |
| `CareerRelevanceEvaluator.evaluateForScope(...)` | New method (additive — the pre-existing `score()`/`explain()` methods are untouched) returning a `CareerRelevanceResult` whose `visible` comes from `CareerThresholdPolicy` instead of the old flat cutoff |

**One class's constructor changed** (additively — new required collaborator, no removed
parameters): `CareerRelevanceEvaluator` and `JobService` each gained one new constructor parameter,
`CareerThresholdPolicy`. `JobService.applyCareerRelevance`'s filter predicate changed from
`score.clearsFeedThreshold()` (hard-coded `>= 70`) to `thresholdPolicy.isVisibleForScope(scopeNorm,
score)` — which reproduces the exact same `>= 70` behavior while `career.soft-thresholds.enabled`
is `false` (the default), so this is a no-behavior-change swap until the flag is flipped.

## Deviations from the literal spec (deliberate, documented)

- **`CareerRelevanceEvaluator` was not modified in place to return `CareerRelevanceResult`** from
  its existing `score()`/`explain()` methods. Doing so would have changed the return type of a
  method `JobService` and `JobRelevanceController` already call, forcing edits to both call sites
  and their tests for no behavioral gain. Instead, a new method `evaluateForScope(...)` was added
  alongside the existing ones — same computation, additive surface. `JobService` was updated to
  use the new `CareerThresholdPolicy` for its filter predicate (the actual behavior change the spec
  wants), while `JobRelevanceController`'s existing `GET /api/jobs/{id}/relevance` response shape is
  untouched, so no existing consumer of that endpoint is affected. Wiring a scope-aware response
  into that controller is left for a future UI phase, consistent with how Phase 3B.1 itself deferred
  its "Step 11" UI fields.
- **Recommended's `>= 85` curation is defined in `CareerThresholdPolicy` (`recommendedThreshold()`,
  `isVisibleForRecommended()`) but not wired into the recommendation engine.** Per the hard
  constraint "do not modify recommendation engine contracts," `RecommendationController` /
  `JobRecommendationService` / `JobMatchingService` were not touched. The policy method exists and
  is unit-tested so a future, explicitly-scoped change to the recommendation engine can consume it
  without re-deriving the threshold logic.

## Architecture review

- **Additive-only.** Three new classes in the existing `discovery.relevance` package; two edited
  files (`CareerRelevanceEvaluator.java`, `JobService.java`) each gained one new constructor
  parameter and no removed behavior.
- **Flag-off is provably a no-op.** `CareerThresholdPolicy.isVisibleForScope` has an explicit
  `if (!softThresholdsEnabled) return score >= LEGACY_FEED_THRESHOLD;` branch — the exact constant
  (`CareerRelevanceScore.FEED_THRESHOLD = 70`) Phase 3B.1 used, not a re-derived magic number.
- **No new persisted state, no new I/O.** `CareerThresholdPolicy` is a pure `@Value`-configured
  policy object; `evaluateForScope` is pure computation over an already-evaluated
  `JobEligibilityEngine.Result`.

## Migration review

**None required or written.** No new schema, no new table, no new column — purely computed values.

## Regression analysis

- **11 new tests** added (`CareerMatchStrengthTest` ×1, `CareerThresholdPolicyTest` ×6,
  `CareerRelevanceEvaluatorTest` ×2 new cases, `JobServiceCareerRelevanceTest` ×2 new cases), all
  green.
- **Full backend suite re-run: 622 tests, 0 failures, 0 errors** (includes Phase 3A, Phase 2E,
  Phase 3B.1, and every other pre-existing suite) — confirms the two constructor-signature changes
  did not break any existing test or code path.
- `JobServiceCareerRelevanceTest.weakScoreIsHiddenUnderLegacyHardCutoff` explicitly asserts a
  score-65 job is still filtered out while `career.soft-thresholds.enabled=false` — the flag-off
  legacy behavior is a test assertion, not just a code-review claim.

## Performance analysis

No new I/O, no new per-job computation beyond one extra `int` comparison
(`thresholdPolicy.isVisibleForScope`) replacing the previous `score.clearsFeedThreshold()` call —
identical cost profile to Phase 3B.1.

## Rollout strategy

See `PHASE_3B_1_1_ROLLBACK.md`. Single new flag, `career.soft-thresholds.enabled` (default
`false`); flipping it alone changes Domestic/International visibility bands without touching any
other Phase 3B.1 flag. Fully reversible — flip back to `false` and the legacy `>= 70` cutoff returns
instantly.

## Explicitly not done (per hard constraints)

No Phase 2E/3A file modified, no recommendation-engine contract changed, no migration/table/cache/
vector-DB/embeddings/Kafka/ElasticSearch/ML introduced, no flag enabled, no docker rebuild, no
deploy, no push.
