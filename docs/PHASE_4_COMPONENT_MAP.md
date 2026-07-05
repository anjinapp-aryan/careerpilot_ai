# Phase 4 — Component Map & Hierarchy

`E` existing · `N` new. Only Phase-4-relevant nodes shown; leaf UI primitives omitted where obvious.

## Shell (existing, unchanged)

```
AppShell (E)
├─ Sidebar (E)              ← nav.ts groups; +2 entries in P4
├─ CommandPalette (E)       ← cmdk; auto-picks up new routes
├─ TopBar (E) ├─ Breadcrumbs · ThemeToggle · NotificationCenter · UserMenu
├─ <Outlet/>               ← routed page
└─ CopilotPanel (E)         ← persistent right rail
```

## 4.1 Dashboard

```
Dashboard (E, extend)
├─ WelcomeBanner + CareerHealthRing (E)
├─ KpiCard ×6 (E) → ×9  [+ OfferProbability, ProfileCompleteness, MarketMatchScore] (N)
├─ ScoreProgression AreaChart (E)
├─ ApplicationPipeline BarChart (E)
├─ PlatformIntelligence (E: Recommendations, JobDiscovery, PlatformHealth, CareerIntelligence)
├─ TodaysOpportunities (N)         ← /api/jobs/recommended?filter=new
├─ PendingApprovals (N)            ← /api/recommendations/human-review
├─ UpcomingInterviews (N)          ← /api/workflow/interviews (dark)
├─ RecentResumeImprovements (N)    ← /api/resume/tailored/history (dark)
├─ CareerProbabilityTrend (N)      ← /api/workflow/career-intelligence (dark)
├─ AIInsights (E)
└─ RecentRuns (E)
```

## 4.2 Resume

```
Resumes (E) ├─ Dropzone · ResumeCard · ResumeVersionsStrip (E, ATS history)
ResumeOptimization (E, extend)
├─ ModeSelector (E)
├─ ATSReport (E)
├─ ApprovalGate (E)
├─ WorkflowStatusStepper (E)
├─ VersionList (E)
└─ TailoringPipeline (N)  Original→Tailored→ATS→Gap  ← /api/resume/tailor*, /api/resume/ats*, /api/diagnostics/gap-analysis (all dark-tolerant)
```

## 4.3 / 4.4 Jobs + Explainability

```
Jobs (E) ├─ Tabs[Recommended·Domestic·International·Saved·Applied·Browse]
├─ RecommendedJobs (E) ├─ RecommendedJobCard ├─ JobBadges (E, extend) └─ ExplainDialog (E)
├─ JobCard (E) ├─ JobBadges (E, extend)
├─ JobBadges (E → extend N): + CareerMatchBadge, MatchStrengthBadge(EXCELLENT/STRONG/GOOD/WEAK),
│                              PriorityBadge, MustApplyBadge
└─ RelevanceDrawer (N)   "Why am I seeing this?"  ← GET /api/jobs/{id}/relevance {score,strength,visible,reasons}
```

## 4.5 Recommendations (new page)

```
Recommendations (N)
├─ PageHeader
├─ Tabs[Critical·High·Medium·Low·Archived] (N)
├─ RecommendationCard (N)
│   ├─ PriorityBadge · ConfidenceBadge · MustApplyBadge
│   ├─ RecommendationReason (text)
│   ├─ BehaviorSignalChip (N)     ← /api/recommendations/behavior-profile
│   └─ Actions: Approve · Reject · Save · Apply Later · Archive (mutations)
└─ EmptyState (dark-tolerant)
   endpoints: GET /api/recommendations (+?priority), /must-apply, /human-review, /audit
              POST /approve /reject /save /archive /feedback
```

## 4.6 Workflow correlation (new tabs on existing page)

```
Workflow (E, extend)
├─ PipelineOverview (E) · WorkflowForm (E) · RunCard (E) · StageDiagnostics (E)
└─ CorrelationExplorer (N)
    ├─ Tabs[Timeline·Graph·Raw Events·Dead Letter]
    ├─ TimelineView (N)     ← GET /api/workflow/correlation/{id}
    ├─ GraphView (N)        ← GET /api/workflow/correlation/{id}/graph
    ├─ RawEventView (N)     ← GET /api/workflow/correlation/{id}/events
    └─ DeadLetterView (N)   ← GET /api/diagnostics/workflow-dead-letter
```

## 4.7 Applications (extend existing Kanban)

```
Applications (E, extend)
├─ DndContext (E)
├─ KanbanColumn ×5 → ×8  [+ Viewed, Withdrawn, Archived] (N)
├─ AppCard (E, extend): + ResumeVersion, WorkflowStatus, CareerProbability (N)
└─ ApplicationDetailDialog (E)  lifecycle + timeline
```

## 4.8 Career Intelligence (new page)

```
CareerIntelligence (N)
├─ ProbabilityCards (Career Success · Interview · Offer) ← /api/workflow/career-intelligence
├─ MarketDemandChart ← /api/admin/stats/skill-heatmap (or career-intel dimensions)
├─ SkillGapChart ← /api/recommendations/behavior-profile
├─ SalaryGrowthChart ← /api/admin/stats/salary-intelligence
└─ RoleTrajectory ← /api/workflow/analytics
   (all dark-tolerant; page renders "not enabled" until workflow analytics active)
```

## 4.9 Admin (extend existing)

```
AdminDashboard (E, extend)
├─ existing: KPIs · ProviderHealth · Discovery · SkillHeatmap · Salary · Observability · Retention · Duplicates
└─ + QueueHealth · ExecutionHealth · CacheHealth · PipelineStageHealth (N)
     ← /api/diagnostics/{resume-tailoring,ats-optimization,gap-analysis,...}/queue,
       /api/diagnostics/match-cache, /api/execution/tracking, /api/diagnostics/application-execution
```

## 4.10 Copilot (extend existing)

```
CopilotPanel (E) — no structural change
└─ copilotActions.ts (E, extend): add page contexts + quick actions
   [explain_rejection, suggest_skills, suggest_applications, market_trends, career_advice]
```

## New-file summary

**Pages (2):** `Recommendations.tsx`, `CareerIntelligence.tsx`.
**Feature dirs (3):** `components/recommendations/`, `components/career/`, `components/relevance/`.
**Notable new components (~14):** `RelevanceDrawer`, `MatchStrengthBadge`, `RecommendationCard`,
`CorrelationExplorer` (+4 views), `TailoringPipeline`, `TodaysOpportunities`, `PendingApprovals`,
`UpcomingInterviews`, `CareerProbabilityTrend`, `ProbabilityCards`, extra Admin health panels.
**New hooks (3):** `useRecommendations`, `useCareerIntelligence`, `useJobRelevance`.
