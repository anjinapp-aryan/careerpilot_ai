# Phase 3B.1 — Filter Rules

## Role family

Four named tech families, resolved from a job title (excluded-keyword check always wins first):

| Family | Trigger titles (default, `@Value`-configurable) |
|---|---|
| `JAVA_BACKEND` | Java Developer, Senior Java Developer, Java Lead, Java Architect, Backend Engineer, Technical Lead, Principal Engineer, Solution Architect, Software Engineer, Backend Developer, Java Engineer |
| `DATA_ENGINEERING` | Data Engineer, Spark Engineer, Big Data Engineer, Data Engineering, ETL Engineer, Analytics Engineer |
| `DEVOPS` | DevOps Engineer, Platform Engineer, SRE, Site Reliability Engineer, Infrastructure Engineer, Cloud Engineer |
| `FRONTEND` | React Developer, UI Engineer, Frontend Engineer/Developer, Front End Developer, Angular Developer |
| `EXCLUDED` | Media, Hospitality, Customer Service, Construction, Sales, Marketing, HR, Creative, BIM |
| `OTHER` | Anything else (fallback) |

**Eligibility:** `EXCLUDED` job → never eligible. Candidate family unknown (`OTHER`) → eligible,
similarity 50 (no false rejection on missing data). Same family → eligible, similarity 100.
Different named families → not eligible, similarity 0. Job family `OTHER` (recognizable role, just
not one of the four) → eligible, similarity 40 (mild, not a rejection).

## Experience

**Rule:** `eligible = jobRequiredExperience >= candidateYears - tolerance` (default
`tolerance = 3`, `career.relevance.experience-tolerance-years`). Either side missing ⇒ no signal ⇒
never rejects.

This exactly reproduces the spec's literal bucket table for a 12-year candidate
(`threshold = 12 - 3 = 9`):

| Job requires | Rule check | Result |
|---|---|---|
| 0-2 yrs | `2 >= 9`? No | **Reject** |
| 2-5 yrs | `5 >= 9`? No | **Reject** |
| 5-8 yrs | `8 >= 9`? No | **Reject** |
| 8-15 yrs | `15 >= 9`? Yes | **Allow** |
| 15+ yrs | `20 >= 9`? Yes | **Allow** |

One-directional by design: never rejects a job for requiring *more* experience than the candidate
has — that's not the "junior-noise" problem this phase targets.

## Skill overlap

`overlap % = matched skill families / required (job) skill families × 100`, using the existing
`JobTaxonomy.skillFamilies()` normalization (so "Spring Boot" and "Spring" count as one family — no
double-penalizing spelling variants). Empty job skills ⇒ 0%. This is a scoring input (30% weight),
not an independent pass/fail gate — `JobEligibilityEngine` only hard-rejects on skills when overlap
is exactly 0% (`REJECTED_SKILLS`).

## Domain / industry

**Excluded** (never eligible regardless of preference): job's classified family is one of
`JobTaxonomy.EXCLUDED_FAMILIES` (Marketing/Sales/HR/Recruiter/Support/Finance — reused, unchanged)
**or** its title/description contains one of the Phase 3B.1-specific keywords not covered by that
taxonomy: Media, Hospitality, Construction, Creative, BIM.

**Preferred:** sourced from the candidate's existing `CandidateProfile.domains` +
`.industries` (AI-derived from the résumé, previously unconsumed by any matcher). An empty/absent
preference list is a neutral pass — it only ever *adds* a soft "Preferred industry" reason, never
a rejection by itself.

## Composite score (`CareerRelevanceScore`)

```
relevanceScore = round(
    roleSimilarity   × 0.40 +
    skillOverlap     × 0.30 +
    (experienceFit ? 100 : 0) × 0.20 +
    (domainFit     ? 100 : 0) × 0.10
)
```

| Score | Band |
|---|---|
| 95-100 | Strong Match |
| 85-94 | Excellent Match |
| 70-84 | Good Match |
| 60-69 | Weak Match |
| < 60 | Hidden |

## Feed gating (Steps 7-9)

| Tab | Rule (when its flag is on) |
|---|---|
| **Domestic** | `country == user country` AND `relevanceScore >= 70`, sorted `relevance DESC, postedDate DESC` |
| **International** | `country != user country` AND `relevanceScore >= 70`, sorted the same way |
| **Browse** | No filters — `browsePool()` is never touched by this phase |

**Known pagination tradeoff:** the relevance filter runs on the already-paginated DB page (mirrors
the existing `applyRoleExclusion` pattern), so a page can return fewer than the requested `size`
once a scope flag is enabled — e.g. a page of 20 fetched jobs might yield 12 after the ≥70 gate.
This was a deliberate choice to keep the change additive and minimal (no repository/query changes)
rather than rewriting to a pool-then-paginate model. If this proves visible in practice, a future
phase can widen the DB fetch size before filtering — out of scope here.
