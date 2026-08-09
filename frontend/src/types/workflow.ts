/**
 * Shared domain types for the AI Workflow surface.
 *
 * These mirror the Spring Boot DTOs returned by the backend:
 *   GET  /api/resumes        -> Resume[]
 *   GET  /api/jobs           -> JobsPage  (Spring Data Page)
 *   POST /api/workflows/run  -> WorkflowRun
 */

/** A resume uploaded by the current user. Mirrors the `resumes` entity. */
export interface Resume {
  id: string;
  /** Backend serializes the JPA field as `filename` (lowercase). */
  filename: string;
  resumeScore?: number | null;
  contentType?: string | null;
  sizeBytes?: number | null;
  updatedAt?: string;
  /** ISO-8601 timestamp, e.g. "2026-06-17T10:00:00Z". */
  createdAt: string;
}

/** A job the workflow can be run against. Mirrors the `jobs` entity. */
export interface Job {
  id: string;
  title: string;
  company: string;
  location?: string | null;
  description?: string;
  salaryRange?: string | null;
  source?: string | null;
  externalUrl?: string | null;
  postedAt?: string | null;
  createdAt?: string;
  // Phase 2 Job Discovery metadata (populated only for ingested/discovered jobs).
  country?: string | null;
  city?: string | null;
  remote?: boolean | null;
  currency?: string | null;
  skills?: string | null;
  sourceUrl?: string | null;
  postedDate?: string | null;
  salaryMin?: number | null;
  salaryMax?: number | null;
  // Recommendation-engine enrichment (keyword-derived at ingest; nullable).
  remoteType?: 'REMOTE' | 'HYBRID' | 'ONSITE' | null;
  sponsorshipAvailable?: boolean | null;
  relocationSupport?: boolean | null;
  companySize?: string | null;
  requiredExperience?: number | null;
}

/** An application tracked in the pipeline. Mirrors the `applications` entity. */
export interface Application {
  id: string;
  jobId: string;
  resumeId?: string | null;
  status: string;
  matchScore?: number | null;
  atsScore?: number | null;
  nextAction?: string | null;
  notes?: string | null;
  createdAt: string;
  updatedAt?: string;
}

/**
 * Phase 7.16 — one Application Submission Pipeline session. Mirrors
 * `ApplicationSubmissionDtos.SessionResponse`. Ships DARK — only populated when
 * `application.submission.enabled` is on (see `lib/applicationSubmission.ts`).
 */
export interface ApplicationSubmissionSession {
  id: string;
  userId: string;
  jobId: string;
  applicationId?: string | null;
  resumeId?: string | null;
  resumeTailoringId?: string | null;
  applicationPackageId?: string | null;
  applicationReviewId?: string | null;
  companyKnowledgeId?: string | null;
  approvalQueueEntryId?: string | null;
  applicationExecutionId?: string | null;
  status: string;
  submissionMethod: string;
  provider?: string | null;
  correlationId?: string | null;
  failureReason?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  createdAt: string;
  updatedAt: string;

  /** Guided Apply — set only after `POST /{id}/report-submitted`. Null on every other status. */
  userReportedSubmittedAt?: string | null;
  userSubmissionNote?: string | null;
}

export interface ApplicationSubmissionAnswer {
  id: string;
  questionText: string;
  questionCategory: string;
  answerText?: string | null;
  sourceRefs?: string | null;
  createdAt: string;
}

export interface ApplicationSubmissionSessionDetail {
  session: ApplicationSubmissionSession;
  answers: ApplicationSubmissionAnswer[];
}

/**
 * Spring Data `Page<Job>` envelope. Only `content` is required by the UI;
 * pagination metadata is kept optional so we tolerate either a full Page
 * payload or a trimmed-down response.
 */
export interface JobsPage {
  content: Job[];
  totalElements?: number;
  totalPages?: number;
  number?: number;
  size?: number;
}

/** Candidate snapshot derived from the user's latest workflow run. Mirrors `CandidateProfileSummary`. */
export interface CandidateProfileSummary {
  yearsExperience?: number | null;
  currentTitle?: string | null;
  topSkills: string[];
  preferredRoles: string[];
  resumeScore?: number | null;
}

/** Per-factor 0-100 sub-scores behind a match score. Mirrors `ScoreBreakdown`.
 *  Weights: skills 35, experience 20, role 15, location 10, salary 10, visa 5, workMode 5. */
export interface ScoreBreakdown {
  skills: number;
  experience: number;
  role: number;
  location: number;
  salary: number;
  visa: number;
  workMode: number;
  /** Phase 6.5 — additive learning boost/penalty already folded into matchScore; 0/absent on older rows. */
  learningBoost?: number;
}

/** A job ranked by the deterministic recommender. Mirrors `RecommendedJob`. */
export interface RecommendedJob {
  job: Job;
  matchScore: number;
  matchedSkills: string[];
  missingSkills: string[];
  /** Nullable: present only on the v2 (persisted) path. */
  confidenceLevel?: 'HIGH' | 'MEDIUM' | 'LOW' | null;
  scoreBreakdown?: ScoreBreakdown | null;
  /** Phase 2B-1 auto-categorization; null unless JOB_AUTO_CATEGORIZATION_ENABLED. */
  category?: string | null;
  /** Phase 2C priority engine; all three null unless the priority engine is enabled. */
  priority?: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | null;
  priorityScore?: number | null;
  mustApply?: boolean | null;
}

/** `GET /api/jobs/{id}/relevance` response (Phase 3B.1 explainability). Mirrors
 *  `CareerRelevanceExplanation`. 404s when `career.explainability.enabled` is false —
 *  callers must treat a failed fetch as "not available", not an error. */
export interface JobRelevance {
  relevanceScore: number;
  /** Human-readable band, e.g. "Strong Match" / "Weak Match" / "Hidden". */
  matchStrength: string;
  roleMatch: boolean;
  skillOverlap: number;
  experienceFit: boolean;
  domainFit: boolean;
  reasons: string[];
}

/** Persistent job preferences. Mirrors `CandidatePreferencesDto`. */
export interface CandidatePreferences {
  /** Candidate's home country — drives the Domestic discovery tab (server-authoritative). */
  homeCountry?: string | null;
  preferredCountries: string[];
  preferredCities: string[];
  preferredRoles: string[];
  /** Role/family names the user never wants recommended (e.g. "Sales", "Marketing"). */
  excludedRoles: string[];
  remotePreference: boolean;
  hybridPreference: boolean;
  onsitePreference: boolean;
  visaSponsorshipRequired: boolean;
  relocationRequired: boolean;
  salaryExpectationMin?: number | null;
  salaryExpectationMax?: number | null;
  salaryCurrency?: string | null;
}

/** Canonical Candidate Intelligence Profile (Phase 1). Mirrors `CandidateProfileDto`.
 *  Read-only on the frontend; produced server-side from resume AI analysis + preferences. */
export interface CandidateProfile {
  resumeId?: string | null;
  yearsExperience?: number | null;
  currentRole?: string | null;
  seniority?: string | null;
  skills: string[];
  targetRoles: string[];
  domains: string[];
  languages: string[];
  /** Was missing from this type until Phase 7.15.3A even though the backend DTO always had it. */
  homeCountry?: string | null;
  preferredCountries: string[];
  preferredCities: string[];
  workModes: string[];
  visaRequired?: boolean | null;
  salaryMin?: number | null;
  salaryTarget?: number | null;
  salaryCurrency?: string | null;
  excludedRoles: string[];
  profileSummary?: string | null;
  confidenceScore?: number | null;
  technologies: string[];
  certifications: string[];
  industries: string[];
  leadershipExperience?: boolean | null;
  cloudExpertise?: boolean | null;
  careerGoals: string[];
  updatedAt?: string | null;
}

/**
 * Phase 8.2 — Resume Intelligence Center. Mirrors `ResumeIntelligenceDtos.ResumeAnalysisStatusDto`.
 * `atsScore` is only ever set from a real completed AI Workflow run for this exact resume — there
 * is no honest job-less ATS score for a bare upload, so it's `null` otherwise (never fabricated).
 */
export interface ResumeAnalysisStatus {
  resumeId: string;
  status: 'NOT_ANALYZED' | 'ANALYZING' | 'ANALYZED' | 'OUTDATED' | 'FAILED' | 'PARTIAL';
  lastAnalysisDate?: string | null;
  analysisDurationMs?: number | null;
  confidenceScore?: number | null;
  atsScore?: number | null;
  errorMessage?: string | null;
}

/** Mirrors `ResumeIntelligenceDtos.ResumeDashboardEntryDto`. */
export interface ResumeDashboardEntry {
  resumeId: string;
  filename: string;
  sizeBytes?: number | null;
  uploadDate: string;
  analysis: ResumeAnalysisStatus;
}

/** Mirrors `ResumeIntelligenceDtos.ResumeAnalysisHistoryEntryDto`. */
export interface ResumeAnalysisHistoryEntry {
  reason: string;
  createdAt: string;
  before: CandidateProfile | null;
  after: CandidateProfile | null;
}

/** `POST /api/jobs/:id/explain` response. Mirrors `JobMatchExplanationDto`. */
export interface JobMatchExplanation {
  matchingSkills: string[];
  missingSkills: string[];
  resumeImprovements: string[];
  atsImprovements: string[];
  modelUsed?: string | null;
}

/** Recommended-tab filter chips. */
export type RecommendedFilter =
  | 'all'
  | 'remote'
  | 'hybrid'
  | 'onsite'
  | 'visa'
  | 'relocation'
  | 'high'
  | 'new'
  | 'must-apply'
  | 'high-priority'
  | 'human-review';

/** `GET /api/jobs/recommended` response. `profile` is null until the user runs the AI workflow. */
export interface RecommendedJobsResponse {
  profile: CandidateProfileSummary | null;
  jobs: RecommendedJob[];
  page?: number;
  size?: number;
  total?: number;
  hasMore?: boolean;
}

/** A persisted, AI-optimized version of a resume. Mirrors `ResumeVersionResponse`. */
export interface ResumeVersion {
  id: string;
  resumeId: string;
  versionNumber: number;
  optimizationMode?: string | null;
  atsBefore?: number | null;
  atsAfter?: number | null;
  providerUsed?: string | null;
  workflowThreadId?: string | null;
  hasDownload: boolean;
  createdAt: string;
}

/** A Resume Optimization target mode (preset role, generic ATS, pasted JD, or existing job). */
export type OptimizationMode =
  | 'generic_ats'
  | 'senior_java_developer'
  | 'java_architect'
  | 'solution_architect'
  | 'enterprise_architect'
  | 'engineering_manager'
  | 'upload_jd'
  | 'select_job';

/** The exact shape of the workflow form's controlled state. */
export interface WorkflowFormState {
  resumeId: string;
  jobIds: string[];
}

/**
 * Payload sent to POST /api/workflows/run.
 *
 * The two required fields match the spec; the index signature lets callers
 * merge in additional backend-expected fields (targetRole, targetSeniority,
 * targetLocations, …) without weakening the core contract.
 */
export interface StartWorkflowPayload extends WorkflowFormState {
  [key: string]: unknown;
}

/** Subset of the WorkflowRun row returned after a run is started. */
export interface WorkflowRun {
  threadId: string;
  status: string;
  targetRole?: string;
  resumeScore?: number | null;
  atsScore?: number | null;
  jobMatchScore?: number | null;
  interviewReadinessScore?: number | null;
  /** Full per-stage agent output blob (candidate_profile, ranked_jobs, agent_execution, cost_usd, …). */
  state?: Record<string, unknown>;
  createdAt?: string;
  updatedAt?: string;
  /** Derived per-stage timeline; the list endpoint returns this too. */
  agents?: WorkflowAgent[];
  // Audit trail (Phase 6) — populated once the approval gate has been actioned.
  approvedBy?: string | null;
  approvedAt?: string | null;
  rejectedBy?: string | null;
  rejectedAt?: string | null;
  feedback?: string | null;
}

// ---------------------------------------------------------------------------
// Workflow status detail — returned by GET /api/workflows/:threadId
// ---------------------------------------------------------------------------

export type AgentStatus =
  | 'COMPLETED'
  | 'ACTIVE'
  | 'PENDING'
  | 'WAITING_FOR_APPROVAL'
  | 'FAILED'
  | 'REJECTED';

export interface WorkflowAgent {
  name: string;
  status: AgentStatus;
  completedAt?: string;
  /** Provider that actually served this stage (e.g. "deepseek", "gemini"). */
  provider?: string | null;
  /** Wall-clock duration of the stage in milliseconds. */
  durationMs?: number | null;
}

export interface WorkflowStatusDetail {
  threadId: string;
  status: string;
  currentAgent?: string;
  agents: WorkflowAgent[];
  /** Full per-stage agent output blob — same shape as WorkflowRun.state. */
  state?: Record<string, unknown>;
}

/** One entry of `state.agent_execution` — per-node execution telemetry (not emitted for Human Approval). */
export interface AgentExecutionEntry {
  stage: string;
  name: string;
  status: string;
  started_at?: string;
  completed_at?: string;
  duration_ms?: number;
  provider?: string;
  error?: string | null;
}

// ---------------------------------------------------------------------------
// WorkflowStatusStepper component props
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Phase 3A follow-up — Workflow Correlation Trace API (WorkflowTraceController).
// Read-only; mirrors WorkflowTraceDtos exactly. All engines ship dark, so a
// correlation id with no data 404s — callers must treat that as "not found",
// not an error.
// ---------------------------------------------------------------------------

export interface WorkflowStepTrace {
  step: string;
  status: 'SUCCESS' | 'FAILED' | 'RUNNING' | 'PENDING' | 'NOT_STARTED' | 'SKIPPED' | string;
  startedAt?: string | null;
  completedAt?: string | null;
  durationMs?: number | null;
}

export interface WorkflowDeadLetterTrace {
  worker: string;
  stage: string;
  error: string;
  timestamp: string;
}

export interface WorkflowTrace {
  correlationId: string;
  workflowType: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'PARTIAL' | string;
  startedAt?: string | null;
  completedAt?: string | null;
  durationMs?: number | null;
  steps: WorkflowStepTrace[];
  deadLetters: WorkflowDeadLetterTrace[];
}

export interface WorkflowSummary {
  correlationId: string;
  workflowType: string;
  status: string;
  totalSteps: number;
  completedSteps: number;
  failedSteps: number;
  deadLetterCount: number;
  durationMs?: number | null;
}

export interface CorrelationDiagnostics {
  correlationId: string;
  workflowType: string;
  status: string;
  totalEvents: number;
  completedStages: number;
  failedStages: number;
  deadLetters: number;
  workflowDurationMs?: number | null;
}

export interface WorkflowGraphNode {
  id: string;
  label: string;
  status: string;
  timestamp?: string | null;
}

export interface WorkflowGraphEdge {
  from: string;
  to: string;
}

export interface WorkflowGraph {
  correlationId: string;
  status: string;
  nodes: WorkflowGraphNode[];
  edges: WorkflowGraphEdge[];
}

export interface WorkflowEventTrace {
  eventId: string;
  correlationId: string;
  eventType: string;
  timestamp: string;
  status: string;
}

/** One row of `GET /api/workflow/career-intelligence`. Mirrors the `CareerIntelligence` entity. */
export interface CareerIntelligenceRow {
  dimension: string;
  dimensionKey?: string | null;
  probability?: number | null;
  sampleSize?: number | null;
  computedAt?: string | null;
}

/** One row of `career_learning`. Mirrors the `CareerLearning` entity. */
export interface CareerLearningRow {
  dimension: string;
  dimensionKey?: string | null;
  score?: number | null;
  sampleSize?: number | null;
  computedAt?: string | null;
}

/** Learned career strategy snapshot. Mirrors the `CareerStrategy` entity. */
export interface CareerStrategySnapshot {
  careerSuccessProbability?: number | null;
  interviewProbability?: number | null;
  offerProbability?: number | null;
  careerGrowthProbability?: number | null;
  marketDemandScore?: number | null;
  recommendedTrajectory?: string | null;
  computedAt?: string | null;
  // Gap C — Career Coach roadmap persistence (V61). Absent/null until a workflow run has been
  // captured with `career.roadmap.persistence.enabled=true`.
  roadmap3Month?: string | null;
  roadmap6Month?: string | null;
  roadmap12Month?: string | null;
  skillGapsJson?: string | null;
  // Phase 7.19 — Career Goal Intelligence (V66). Absent/null until POST /career-goal/recompute or
  // /career-goal/plan has run with the corresponding career.* flag on. Raw JSON, parsed client-side.
  skillGapIntelligenceJson?: string | null;
  promotionReadinessJson?: string | null;
  careerGoalJson?: string | null;
  computedRoadmapJson?: string | null;
  careerGoalComputedAt?: string | null;
}

/** `GET /api/workflow/career-learning` response (Phase 6.5). Absent/empty fields when
 *  `learning.adaptive-career.enabled` is off. */
export interface CareerLearningView {
  strategy?: CareerStrategySnapshot | null;
  topCompanies: CareerLearningRow[];
  topSkills: CareerLearningRow[];
  topIndustries: CareerLearningRow[];
  topLocations: CareerLearningRow[];
  topSalaryBands: CareerLearningRow[];
}

/** Phase 7.19.5 — one item from `GET /api/workflow/executive/decisions`. Every field is traceable
 *  to an existing intelligence module; nothing here is a newly-computed score. */
export interface ExecutiveDecision {
  type: string;
  recommendation: string;
  evidence: Record<string, unknown>;
  confidence: 'HIGH' | 'MEDIUM' | 'LOW' | string;
  modulesUsed: string[];
  timestamp: string;
}

/** `GET /api/workflow/executive/decisions` response. Empty object when `executive.decision.enabled` is off. */
export interface ExecutiveDecisionsResponse {
  careerHealth?: { value: number | string; source?: string; evidence?: string; reason?: string } | null;
  decisions?: ExecutiveDecision[];
  omittedDecisionTypes?: string[];
  sourceModules?: string[];
  computedAt?: string;
}

/** One row of `GET /api/recommendations/audit`. Mirrors the `recommendation_audit` entity. */
export interface RecommendationAuditRow {
  id: string;
  jobId: string;
  profileVersion?: string | null;
  profileSource: string;
  skillScore: number;
  roleScore: number;
  preferenceScore: number;
  locationScore: number;
  visaScore: number;
  salaryScore: number;
  finalScore: number;
  decision?: string | null;
  createdAt: string;
}

/** `GET /api/recommendations/behavior-profile`. Mirrors `CandidateBehaviorProfile` — the
 *  preference fields are serialized text (JSON array or CSV), parsed client-side. */
export interface BehaviorProfile {
  userId?: string;
  preferredRoles?: string | null;
  rejectedRoles?: string | null;
  preferredCountries?: string | null;
  rejectedCountries?: string | null;
  preferredWorkModes?: string | null;
  rejectedWorkModes?: string | null;
  preferredDomains?: string | null;
  rejectedDomains?: string | null;
  updatedAt?: string | null;
}

/** One row of `GET /api/recommendations/feedback`. Mirrors `RecommendationFeedback`. */
export interface RecommendationFeedbackRow {
  id: string;
  jobId: string;
  action: string;
  reason?: string | null;
  createdAt: string;
}

/** One entry of `GET /api/execution/pending` (Phase 2E approval queue). Mirrors `ApprovalQueueEntry`. */
export interface ApprovalQueueItem {
  id: string;
  jobId: string;
  applicationPackageId: string;
  safetyVerdict: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | string;
  decidedBy?: string | null;
  decisionNote?: string | null;
  requestedAt: string;
  decidedAt?: string | null;
  expiresAt?: string | null;
}

/** One entry of `GET /api/candidate-profile/history`. Mirrors `CandidateProfileHistoryDto`. */
export interface CandidateProfileHistoryEntry {
  reason: string;
  createdAt: string;
  before?: CandidateProfile | null;
  after?: CandidateProfile | null;
}

/** One row of `GET /api/resume/tailored/history` (`versions[]`). Mirrors `TailoredResumeResponse`. */
export interface TailoredResumeVersion {
  id: string;
  jobId: string;
  version: string;
  atsBefore?: number | null;
  atsAfter?: number | null;
  improvementScore?: number | null;
  status: string;
  createdAt: string;
}

/** One row of `GET /api/resume/ats/history?jobId=`. Mirrors `AtsAnalysisResponse`. */
export interface AtsAnalysisRow {
  id: string;
  jobId: string;
  resumeTailoringId: string;
  atsScore?: number | null;
  matchedKeywords: string[];
  missingKeywords: string[];
  suggestions: string[];
  status: string;
  createdAt: string;
}

/** One row of `GET /api/workflow/analytics`. Mirrors the `ApplicationAnalytics` entity. */
export interface ApplicationAnalyticsRow {
  metric: string;
  value?: number | null;
  dimensionKey?: string | null;
  windowStart?: string | null;
  computedAt?: string | null;
}

// ---------------------------------------------------------------------------
// Phase 5 — Daily Job Discovery Agent. Ships dark; every field is nullable/
// optional because the agent may never have run for a given user or at all.
// ---------------------------------------------------------------------------

/** The `dailyDiscovery` section of `GET /api/dashboard` (DashboardService additive field). Null when the agent has never run for this user. */
export interface DailyDiscoverySnapshot {
  computedAt?: string | null;
  recommendedJobs?: number | null;
  mustApplyJobs?: number | null;
  highPriorityJobs?: number | null;
  humanReviewJobs?: number | null;
  domesticJobs?: number | null;
  internationalJobs?: number | null;
  averageScore?: number | null;
  /** JSON-encoded `{label: count}` maps — parse client-side. */
  industryDistribution?: string | null;
  skillDistribution?: string | null;
  companyDistribution?: string | null;
  matchStrengthDistribution?: string | null;
  summaryText?: string | null;
  /** Comma-joined. */
  topCompanies?: string | null;
  topSkills?: string | null;
  interviewProbabilityDelta?: number | null;
  offerProbabilityDelta?: number | null;
}

/** `GET /api/daily-discovery/summary`. Mirrors the `DailyCareerSummary` entity. 404s until the agent has run once. */
export interface DailyDiscoverySummary {
  id: string;
  runId: string;
  userId: string;
  summaryText?: string | null;
  jobsFetched?: number | null;
  jobsDeduped?: number | null;
  recommendedCount?: number | null;
  mustApplyCount?: number | null;
  highPriorityCount?: number | null;
  topCompanies?: string | null;
  topSkills?: string | null;
  interviewProbabilityDelta?: number | null;
  offerProbabilityDelta?: number | null;
  createdAt: string;
}

/** One row of `GET /api/daily-discovery/analytics` (user-scoped history). Mirrors `DailyDiscoveryAnalytics`. */
export interface DailyDiscoveryAnalyticsRow {
  id: string;
  runId: string;
  userId?: string | null;
  domesticJobs?: number | null;
  internationalJobs?: number | null;
  recommendedJobs?: number | null;
  mustApplyJobs?: number | null;
  highPriorityJobs?: number | null;
  humanReviewJobs?: number | null;
  hiddenJobs?: number | null;
  averageScore?: number | null;
  industryDistribution?: string | null;
  skillDistribution?: string | null;
  companyDistribution?: string | null;
  matchStrengthDistribution?: string | null;
  computedAt: string;
}

/** `GET /api/diagnostics/daily-discovery`. No-auth scheduler health snapshot. */
export interface DailyDiscoverySchedulerHealth {
  schedulerEnabled: boolean;
  analyticsEnabled: boolean;
  summaryEnabled: boolean;
  totalRuns: number;
  successfulRuns: number;
  failedRuns: number;
  usersProcessed: number;
  lastRunAt?: string | null;
  lastSuccessAt?: string | null;
  lastStatus: string;
  lastDurationMs: number;
  health: string;
}

/** One entry of `GET /api/diagnostics/daily-discovery/providers`. */
export interface DailyDiscoveryProviderHealth {
  configured: boolean;
  flagEnabled?: boolean;
  health: string;
  lastStatus?: string | null;
  lastStartedAt?: string | null;
  lastFinishedAt?: string | null;
  lastJobsFetched?: number | null;
  lastJobsPersisted?: number | null;
  reason?: string | null;
  targetCompanies?: string[];
}

export interface DailyDiscoveryProvidersResponse {
  greenhouse: DailyDiscoveryProviderHealth;
  lever: DailyDiscoveryProviderHealth;
  remoteok: DailyDiscoveryProviderHealth;
  wellfound: DailyDiscoveryProviderHealth;
  companyCareerSites: DailyDiscoveryProviderHealth;
}

/** One entry of `GET /api/diagnostics/job-providers/providers` (JobDiscoveryHealthTracker-backed). */
export interface JobProviderHealth {
  provider: string;
  configured: boolean;
  circuitState: 'CLOSED' | 'OPEN' | 'HALF_OPEN';
  totalRuns: number;
  successRuns: number;
  failureRuns: number;
  successRate: number;
  lastLatencyMs: number;
  avgLatencyMs: number;
  lastJobsFetched: number;
  lastJobsAccepted: number;
  lastJobsRejected: number;
  lastRunAt?: string | null;
  lastError?: string | null;
  health: 'UP' | 'DEGRADED' | 'DOWN' | 'NEVER_RUN' | 'NOT_CONFIGURED';
}

/** `GET /api/diagnostics/job-providers/providers` — keyed by provider name (Greenhouse/Lever/Ashby/…). */
export type JobProviderHealthResponse = Record<string, JobProviderHealth>;

/** `GET /api/diagnostics/job-providers/discovery`. */
export interface JobDiscoverySchedulerSummary {
  dailySchedulerEnabled: boolean;
  hourlySchedulerEnabled: boolean;
  cron: string;
  providersRegistered: number;
  providersConfigured: number;
  recentAuditRows: number;
  recentJobsFetched: number;
  recentJobsImported: number;
  recentJobsRejected: number;
  recentFailedRuns: number;
  lastRunAt?: string | null;
}

/** Phase 5.3.1 — `GET /api/diagnostics/job-providers/enterprise` (extends the Phase 5.3 shape additively). */
export interface EnterpriseAtsDiagnostics {
  masterEnabled: boolean;
  workday: JobProviderHealth & { flagEnabled: boolean; companyCount: number };
  taleo: JobProviderHealth & { flagEnabled: boolean; companyCount: number; note: string };
  successfactors: JobProviderHealth & { flagEnabled: boolean; companyCount: number; note: string };
  configuredCompanies: number;
  healthyCompanies: number;
  failedCompanies: number;
  disabledCompanies: number;
  jobsImportedTotal: number;
  averageJobsPerConnector: number;
  topPerformingConnector: string | null;
  worstConnector: string | null;
  jobsImportedToday: number;
  averageSyncDurationMs: number;
}

/** Phase 5.3.1 — one row from `GET /api/enterprise/connectors`. */
export interface CompanyConnector {
  id: string;
  companyName: string;
  atsType: 'WORKDAY' | 'TALEO' | 'SUCCESSFACTORS';
  careerUrl?: string | null;
  tenant?: string | null;
  cluster?: string | null;
  site?: string | null;
  country?: string | null;
  industry?: string | null;
  priority: number;
  enabled: boolean;
  healthStatus: string;
  lastSuccessfulSync?: string | null;
  lastFailure?: string | null;
  lastFailureReason?: string | null;
  failureCount: number;
  averageLatencyMs: number;
  jobsImported: number;
  createdAt: string;
  updatedAt: string;
}

/** Phase 5.3.1 — `POST /api/enterprise/connectors/{id}/sync` and the `sync-all`/`sync-failed` list variants. */
export interface ConnectorSyncResult {
  connectorId: string;
  company: string;
  atsType: string;
  success: boolean;
  jobsFetched: number;
  jobsImported: number;
  latencyMs: number;
  error?: string | null;
}

/** Phase 5.3.1 — `GET /api/enterprise/connectors/statistics`. */
export interface ConnectorStatistics {
  total: number;
  enabled: number;
  disabled: number;
  healthy: number;
  failed: number;
  jobsImportedTotal: number;
  averageJobsPerConnector: number;
  topPerformingConnector: string | null;
  worstConnector: string | null;
}

export interface WorkflowStatusStepperProps {
  workflowId: string;
  agents?: WorkflowAgent[];
  currentAgent?: string;
  variant?: 'vertical' | 'horizontal';
  className?: string;
  /** Full per-stage state blob, passed through so stage rows can render real detail without an extra fetch. */
  state?: Record<string, unknown>;
  /** Externally requested stage name to expand + scroll into view (e.g. from the top pipeline). */
  focusStage?: string | null;
  /** A changing token so the same stage can be re-focused even if it was already open. */
  focusNonce?: number;
  /** Horizontal-only: bubbles a stage click up for navigation (does not expand anything locally). */
  onStageNavigate?: (stageName: string) => void;
}

/* ────────────────────────────────────────────────────────────────────────────────────────────
 * Applications Page — AI Job Application Command Center
 * Mirrors ai.careerpilot.applications.dto.ApplicationCardDtos.ApplicationCardResponse.
 * ──────────────────────────────────────────────────────────────────────────────────────────── */

export type ApplicationHealthStatus = 'EXCELLENT' | 'HEALTHY' | 'NEEDS_ATTENTION' | 'RISK' | 'COLD' | 'STALE';

export type ApplicationRecommendationAction =
  | 'WAIT'
  | 'FOLLOW_UP_NOW'
  | 'WITHDRAW'
  | 'REAPPLY_LATER'
  | 'IMPROVE_RESUME'
  | 'IMPROVE_COVER_LETTER'
  | 'OUTREACH'
  | 'NETWORK';

export type ApplicationPriority = 'LOW' | 'MEDIUM' | 'HIGH';

/** `GET /api/applications/cards` and `GET /api/applications/{id}` response shape. */
export interface ApplicationCard {
  id: string;
  jobId: string;
  resumeId?: string | null;
  status: string;
  matchScore?: number | null;
  atsScore?: number | null;
  nextAction?: string | null;
  nextActionAt?: string | null;
  notes?: string | null;
  favorite: boolean;
  priority: ApplicationPriority;
  archived: boolean;
  createdAt: string;
  updatedAt?: string;

  jobTitle?: string | null;
  company?: string | null;
  location?: string | null;
  salaryRange?: string | null;
  remoteType?: 'REMOTE' | 'HYBRID' | 'ONSITE' | null;
  source?: string | null;
  externalUrl?: string | null;
  sponsorshipAvailable?: boolean | null;
  /** Guided Apply — the employer's own job identifier (Job.externalId), when the source recorded one. */
  employerJobId?: string | null;

  resumeTailored: boolean;
  atsAnalysisReady: boolean;
  coverLetterReady: boolean;
  applicationPackageReady: boolean;
  applicationReviewReady: boolean;

  visaRequired?: boolean | null;

  healthStatus: ApplicationHealthStatus;
  healthScore: number;
  healthReasoning: string;
  recommendationAction: ApplicationRecommendationAction;
  recommendationReasoning: string;
  suggestedNextAction: string;
  suggestedNextActionAt?: string | null;

  /** Phase 3A lifecycle status (one of the 16 real statuses), or null when tracking has no row yet. */
  lifecycleStatus?: string | null;

  /**
   * Phase 7.16.3/7.16.4 — Automation Recovery Center visibility. All null unless
   * application.execution.enabled and an ApplicationExecution row exists for this (user, job).
   * automationHealth is a derived display label (RUNNING/WAITING/RETRYING/MANUAL_REVIEW/RECOVERED/
   * COMPLETED/VERIFICATION_FAILED/FAILED), not a persisted status.
   */
  executionId?: string | null;
  executionStatus?: string | null;
  automationHealth?: string | null;
  retryCount?: number | null;
  verificationStatus?: string | null;

  /**
   * Guided Apply — populated only when `automationHealth === 'GUIDED_APPLY_REQUIRED'` (the latest
   * execution is ABORTED). `blockerReason` is the deterministic classification (CAPTCHA/
   * BOT_PROTECTION/LOGIN_REQUIRED/UNSUPPORTED_CONTROL/EMPLOYER_RESTRICTION/AUTOMATION_BLOCKED/
   * MANUAL_REQUIRED/UNKNOWN_BLOCKER); `blockerDetail` is the underlying raw reason text. Both null
   * otherwise — never fabricated.
   */
  guidedApplyRequired: boolean;
  blockerReason?: string | null;
  blockerDetail?: string | null;
}

/** Guided Apply — one candidate-profile fact shown in the Application Profile section. */
export interface GuidedApplyProfileFact {
  label: string;
  value: string;
  source: string;
}

/**
 * Guided Apply — one predictable application question, resolved (or honestly not) against
 * verified data. `confidence` is null exactly when `needsUserInput` is true.
 */
export interface GuidedApplyRecommendedAnswer {
  question: string;
  canonicalField: string;
  value?: string | null;
  source?: string | null;
  confidence?: 'HIGH' | 'MEDIUM' | 'LOW' | null;
  needsUserInput: boolean;
}

/** `GET /api/applications/{id}/guided-apply-brief` response. */
export interface GuidedApplyBrief {
  candidateName?: string | null;
  candidateEmail?: string | null;
  resumeFilename?: string | null;
  profile: GuidedApplyProfileFact[];
  recommendedAnswers: GuidedApplyRecommendedAnswer[];
}

/** One row of `ExecutionEvidence.timeline` — mirrors `ExecutionTimelineService#renderStages`. */
export interface ExecutionStageEntry {
  sequence: number;
  stage: string;
  displayName: string;
  status: 'STARTED' | 'COMPLETED' | 'FAILED' | 'SKIPPED';
  startedAt?: string | null;
  endedAt?: string | null;
  durationMs?: number | null;
  failureCategory?: string | null;
  reason?: string | null;
  detail?: string | null;
}

/**
 * `GET /api/applications/{id}/execution-evidence` response (P7 Action 7 — Execution Visibility).
 * Category A only (automation execution evidence) — every boolean/count is either proven by a
 * specific recorded stage or explicitly `null` ("unknown"), never fabricated. See
 * `ExecutionEvidenceService`'s own javadoc for the derivation rules.
 */
export interface ExecutionEvidence {
  hasExecution: boolean;
  executionId?: string | null;
  executionStatus?: string | null;
  instrumentationEnabled: boolean | null;
  automationStarted: boolean;
  state: 'NOT_STARTED' | 'STARTED' | 'EMPLOYER_PAGE_REACHED' | 'FORM_DISCOVERED' | 'FILLING'
    | 'PARTIALLY_FILLED' | 'STOPPED' | 'COMPLETED' | 'UNKNOWN';
  employerPageReached?: boolean | null;
  formDiscovered?: boolean | null;
  /** Single field, not separate captcha/login booleans — the backend's own `CaptchaLoginDetector`
   *  returns one combined signal and cannot honestly be split further. See
   *  `ExecutionEvidenceService`'s javadoc for why. */
  captchaOrLoginDetected?: boolean | null;
  fieldsDiscovered?: number | null;
  fieldsFilled?: number | null;
  fieldsResolved?: number | null;
  fieldsUnresolved?: number | null;
  automationStopped?: boolean;
  stopReason?: string | null;
  stoppedAtStage?: string | null;
  failureCategory?: string | null;
  timeline: ExecutionStageEntry[];
}

export type ApplicationBulkAction = 'STATUS' | 'ARCHIVE' | 'NOTES' | 'NEXT_ACTION' | 'RESUME' | 'EXPORT';

export interface ApplicationBulkRequest {
  ids: string[];
  action: ApplicationBulkAction;
  payload?: Record<string, string>;
}

export interface ApplicationBulkResult {
  requested: number;
  applied: number;
  failedIds: string[];
}

/** `GET /api/workflow/applications/{jobId}/lifecycle` — Phase 3A lifecycle row + status history. */
export interface LifecycleView {
  lifecycle?: {
    currentStatus?: string;
    previousStatus?: string | null;
    company?: string | null;
    country?: string | null;
    applicationDate?: string | null;
    source?: string | null;
    updatedAt?: string | null;
  } | null;
  history?: { fromStatus?: string | null; toStatus?: string | null; createdAt?: string | null }[];
}

/** `GET /api/workflow/applications/{jobId}/timeline` — Phase 3A observable-event timeline. */
export interface TimelineEntry {
  id: string;
  eventType: string;
  eventSource?: string | null;
  confidence?: number | null;
  details?: string | null;
  occurredAt?: string | null;
}

/** Gap B — Offer Intelligence & Salary Negotiation. Mirrors the `Offer` entity (V61). */
export interface Offer {
  id: string;
  userId: string;
  jobId?: string | null;
  companyName?: string | null;
  baseSalary?: number | null;
  bonus?: number | null;
  rsuValue?: number | null;
  equityDescription?: string | null;
  benefitsSummary?: string | null;
  joiningBonus?: number | null;
  currency?: string | null;
  source: 'MANUAL' | 'SALARY_INTELLIGENCE_AGENT';
  marketP25?: number | null;
  marketP50?: number | null;
  marketP75?: number | null;
  marketP90?: number | null;
  negotiationStrategy?: string | null;
  leveragePoints?: string | null;
  sourceThreadId?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

/** Body for `POST /api/offers` — manual offer entry. */
export interface ManualOfferRequest {
  jobId?: string | null;
  companyName?: string | null;
  baseSalary?: number | null;
  bonus?: number | null;
  rsuValue?: number | null;
  equityDescription?: string | null;
  benefitsSummary?: string | null;
  joiningBonus?: number | null;
  currency?: string | null;
}

/** One row of `GET /api/offers/compare` — deterministic per-component comparison. */
export interface OfferComparisonRow {
  offerId: string;
  companyName?: string | null;
  currency?: string | null;
  components: {
    baseSalary: number;
    bonus: number;
    rsuValue: number;
    joiningBonus: number;
    totalComp: number;
  };
  deltaFromHighest?: number | null;
}

/** `GET /api/offers/compare` response. */
export interface OfferComparisonResult {
  rows: OfferComparisonRow[];
  highestOfferId?: string | null;
}

/** One row from `GET /api/career-memory`, `/timeline`, or `?category=`. */
export interface CareerDecisionMemory {
  id: string;
  userId: string;
  decisionType: string;
  category: string;
  value?: string | null;
  reason?: string | null;
  confidence?: number | null;
  source: string;
  importance?: number | null;
  aiGenerated?: boolean | null;
  userConfirmed?: boolean | null;
  expiresAt?: string | null;
  jobId?: string | null;
  correlationId?: string | null;
  workflowId?: string | null;
  usageCount?: number | null;
  lastUsedAt?: string | null;
  createdAt: string;
}

/** `GET /api/career-memory/summary` — the "AI Learned About You" header stats. */
export interface CareerMemorySummary {
  totalMemories: number;
  verifiedCount: number;
  needsReviewCount: number;
  conflictingCount: number;
  lowConfidenceCount: number;
  averageConfidence: number;
  lastUpdated?: string | null;
}

/**
 * `GET /api/candidate-profile` response shape — this is the SAME wire shape as the existing
 * {@link CandidateProfile} type above (this file already had one before Phase 7.15.3; that
 * earlier addition duplicated it under a different name — fixed by aliasing instead).
 */
export type CandidateProfileDto = CandidateProfile;

/** `POST /api/career-memory` body — "Edit Memory". */
export interface EditMemoryRequest {
  category: string;
  value: string;
  reason?: string | null;
}

/** One row of `GET /api/interviews`. */
export interface InterviewFeedbackEntry {
  feedback?: string | null;
  rating?: number | null;
  createdAt: string;
}
export interface InterviewDto {
  id: string;
  jobId: string;
  interviewType: string;
  interviewer?: string | null;
  durationMinutes?: number | null;
  result?: string | null;
  scheduledAt?: string | null;
  createdAt: string;
  feedback: InterviewFeedbackEntry[];
}

/** `GET /api/diagnostics/career-memory` — public, aggregate-only (never per-user). */
export interface CareerMemoryDiagnostics {
  enabled: boolean;
  copilotContextEnabled: boolean;
  totalMemories: number;
  executorActiveCount: number;
  executorQueueSize: number;
  health: string;
  extractionAttempts?: number;
  extractionSuccesses?: number;
  extractionFailures?: number;
  retrievalCount?: number;
  avgRetrievalLatencyMs?: number;
  byCategory?: Record<string, number>;
  bySource?: Record<string, number>;
  averageConfidence?: number | null;
  freshness?: { createdLast7d: number; createdLast30d: number; createdLast90d: number };
  topRemembered?: { decisionType: string; value: string; uses: number }[];
  conversationIntelligence?: {
    enabled: boolean;
    conversationsAnalyzed?: number;
    decisionsDetected?: number;
    memoriesAccepted?: number;
    rejectedLowConfidence?: number;
    duplicatesSkipped?: number;
    writeFailures?: number;
    averageConfidence?: number;
    avgProcessingLatencyMs?: number;
    memoryWriteSuccessRate?: number;
  };
}
