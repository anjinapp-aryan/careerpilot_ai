import { api } from '@/lib/api';
import type { ExecutionEvidence } from '@/types/workflow';

/**
 * P7 Action 7 — Execution Visibility. `GET /api/applications/{id}/execution-evidence` always
 * returns 200 (never 404 for a dark flag — see `ApplicationController#executionEvidence`), so a
 * thrown error here means something genuinely went wrong, not "feature disabled"; that state is
 * surfaced honestly rather than silently swallowed to null like the guided-apply-brief client.
 */
export const executionEvidence = {
  get: async (applicationId: string): Promise<ExecutionEvidence> =>
    (await api.get(`/api/applications/${applicationId}/execution-evidence`)).data,
};
