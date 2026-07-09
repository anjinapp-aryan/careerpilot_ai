# Phase 3B.1.1 — Match Strength

## `CareerMatchStrength`

New enum replacing the ad-hoc string labels `CareerRelevanceScore.matchStrengthFor(int)` produced
in Phase 3B.1 (`"Strong Match"`, `"Excellent Match"`, ... — kept, unmodified, for backward
compatibility with `CareerRelevanceExplanation`). `CareerMatchStrength` is the typed, Phase-3B.1.1
equivalent used by the new `CareerRelevanceResult`:

| Band | Score range |
|---|---|
| `EXCELLENT` | 90-100 |
| `STRONG` | 80-89 |
| `GOOD` | 70-79 |
| `WEAK` | 60-69 |
| `HIDDEN` | 0-59 |

```java
CareerMatchStrength.fromScore(int score)
```

Pure function, boundary-inclusive on the lower edge of each band (`fromScore(90) == EXCELLENT`,
`fromScore(89) == STRONG`), covered by `CareerMatchStrengthTest` at every boundary (59/60, 69/70,
79/80, 89/90).

## Relationship to the pre-existing string bands

Phase 3B.1's `CareerRelevanceScore.matchStrengthFor(int)` bands (95/85/70/60) are **not** the same
boundaries as `CareerMatchStrength` (90/80/70/60) — the spec for 3B.1.1 explicitly redefines the
top two bands (95→90 for the top tier, 85→80 for the second tier) while keeping `GOOD`/`WEAK`/
`HIDDEN` boundaries the same as `CareerRelevanceScore`'s `"Good Match"`/`"Weak Match"`/`"Hidden"`.
Both enums/methods coexist: `CareerRelevanceScore.matchStrengthFor` still backs the existing,
unmodified `CareerRelevanceExplanation` (Phase 3B.1's `GET /api/jobs/{id}/relevance`);
`CareerMatchStrength.fromScore` backs the new `CareerRelevanceResult` (`evaluateForScope`). No
existing consumer's band labels changed.

## UI compatibility

No UI redesign, per spec. The additive fields available for a future frontend iteration:

```json
{
  "relevanceScore": 68,
  "matchStrength": "WEAK",
  "visible": true
}
```

`matchStrength` here is `CareerMatchStrength`'s enum name (serializes as a plain string via
Jackson's default enum handling) — the frontend can render `"68% Weak Match"` directly from these
three fields without any additional lookup.
