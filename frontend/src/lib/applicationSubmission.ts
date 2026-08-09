import { api } from '@/lib/api';
import type {
  ApplicationSubmissionSession,
  ApplicationSubmissionSessionDetail,
} from '@/types/workflow';

/**
 * Phase 7.16 — Application Submission Pipeline API client. Ships DARK on the backend
 * (`application.submission.enabled=false`), and the diagnostics endpoint always exists (it just
 * reports `enabled:false`) — mirrors `companyIntel.ts`'s `nullOn404` dark-flag pattern for the
 * session endpoints, which 500/404 when the master flag is off.
 */

export interface ApplicationSubmissionDiagnostics {
  enabled: boolean;
  autoEnabled: boolean;
  manualEnabled: boolean;
  approvalEnabled: boolean;
  sessionsTotal: number;
  sessionsCompleted: number;
  sessionsFailed: number;
  sessionsWaitingApproval: number;
  sessionsSubmitting: number;
  executorActiveCount: number;
  executorPoolSize: number;
  executorQueueSize: number;
  executorQueueCapacity: number;
  health: 'NOT_CONFIGURED' | 'UP' | 'DEGRADED' | 'DOWN';
}

async function nullOnError<T>(req: Promise<{ data: T }>): Promise<T | null> {
  try {
    return (await req).data;
  } catch {
    return null;
  }
}

export const applicationSubmission = {
  diagnostics: () =>
    nullOnError<ApplicationSubmissionDiagnostics>(api.get('/api/diagnostics/application-submission')),

  /** Returns null when the pipeline is disabled/unreachable — caller falls back to legacy Apply. */
  isEnabled: async (): Promise<boolean> => {
    const d = await applicationSubmission.diagnostics();
    return d?.enabled === true;
  },

  start: (jobId: string, resumeId?: string | null) =>
    nullOnError<ApplicationSubmissionSession>(
      api.post('/api/application-submission', { jobId, resumeId: resumeId ?? null }),
    ),

  /** The current user's submission sessions, newest first. Empty array when disabled/unreachable. */
  list: async (): Promise<ApplicationSubmissionSession[]> =>
    (await nullOnError<ApplicationSubmissionSession[]>(api.get('/api/application-submission'))) ?? [],

  get: (id: string) =>
    nullOnError<ApplicationSubmissionSessionDetail>(api.get(`/api/application-submission/${id}`)),

  status: (id: string) =>
    nullOnError<ApplicationSubmissionSession>(api.get(`/api/application-submission/${id}/status`)),

  history: (id: string) =>
    nullOnError<ApplicationSubmissionSession[]>(api.get(`/api/application-submission/${id}/history`)),

  queue: () => nullOnError<ApplicationSubmissionSession[]>(api.get('/api/application-submission/queue')),

  /**
   * Guided Apply — the candidate explicitly confirms they completed the employer's application
   * themselves. Deliberately does NOT swallow errors like the read methods above: a 409 (wrong
   * session state) must reach the caller so the UI can show it, rather than silently no-op'ing on
   * an explicit user action.
   */
  reportSubmitted: async (id: string, note?: string | null): Promise<ApplicationSubmissionSession> =>
    (await api.post(`/api/application-submission/${id}/report-submitted`, { note: note ?? null })).data,
};
