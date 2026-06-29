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
  | 'new';

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
