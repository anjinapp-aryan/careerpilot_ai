import { api } from '@/lib/api';

/**
 * Fire-and-forget UI telemetry for the Jobs surface (filter usage, apply/save/why-match clicks).
 * Never throws and never blocks the UI — failures are swallowed.
 */
export function trackJobEvent(event: string, opts?: { jobId?: string; filter?: string }) {
  api
    .post('/api/jobs/telemetry', { event, jobId: opts?.jobId ?? null, filter: opts?.filter ?? null })
    .catch(() => {
      /* telemetry is best-effort */
    });
}
