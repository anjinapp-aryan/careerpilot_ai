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
  state?: string;
  createdAt?: string;
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
}
