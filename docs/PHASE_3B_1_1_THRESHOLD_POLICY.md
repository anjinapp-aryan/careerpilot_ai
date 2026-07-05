# Phase 3B.1.1 — Threshold Policy

## `CareerThresholdPolicy`

`@Component`, four configurable per-feed thresholds plus one master flag:

| Property | Default | Meaning |
|---|---|---|
| `career.soft-thresholds.enabled` | `false` | Master switch. When `false`, every feed's visibility reproduces the exact pre-3B.1.1 (`>= 70`) behavior regardless of the values below. |
| `career.relevance.thresholds.recommended` | `85` | Cutoff for `isVisibleForRecommended` — **not wired into any controller** (recommendation engine contracts are frozen); available for a future phase. |
| `career.relevance.thresholds.domestic` | `60` | Cutoff for `isVisibleForScope("domestic", score)` when the master flag is on. |
| `career.relevance.thresholds.international` | `60` | Cutoff for `isVisibleForScope("international", score)` when the master flag is on. |
| `career.relevance.thresholds.hidden` | `60` | Documents the HIDDEN/visible boundary; currently the same value as the domestic/international default (`hiddenThreshold()` accessor, unused by any filter directly — visibility is driven by the per-scope thresholds). |
| `career.relevance.thresholds.browse` | n/a (hard-coded `0`) | `browseThreshold()` always returns `0` — Browse is unfiltered by construction; this exists only for API completeness alongside the other three feeds. |

## Visibility rules

```
isVisibleForScope(scope, score):
    if !softThresholdsEnabled:
        return score >= 70                      # legacy hard cutoff, unchanged
    threshold = domesticThreshold   if scope == "domestic"
              else internationalThreshold        # also the fallback for any other scope string
    return score >= threshold

isVisibleForRecommended(score):
    return score >= recommendedThreshold          # always active; not scope-dependent

isVisibleForBrowse(score):
    return score >= 0                             # always true
```

## Business-requirement traceability

| Spec example | Rule applied | Result |
|---|---|---|
| Domestic 92/84/74/65 | soft on, `score >= 60` | all visible |
| Domestic 59 and below | soft on, `score >= 60` | hidden |
| International 95/81/71/63 | soft on, `score >= 60` | all visible |
| International 59 and below | soft on, `score >= 60` | hidden |
| Recommended 95/92/89 | `score >= 85` | visible |
| Java Architect (68), Lead Backend Engineer (72), Principal Software Engineer (74) | soft on, `score >= 60` | **all three now visible** — this is the exact motivating example from the spec's problem statement |

## Interaction with Phase 3B.1's existing flags

`career.soft-thresholds.enabled` is independent of `career.relevance.enabled` and the per-scope
`career.domestic.filter.enabled` / `career.international.filter.enabled` flags from Phase 3B.1.
`JobService.applyCareerRelevance` still short-circuits to a no-op unless
`career.relevance.enabled` AND the relevant scope filter flag are both on — `career.soft-
thresholds.enabled` only changes *which cutoff* is applied once filtering is already active. Browse
is never touched by any of these flags, in any combination.
