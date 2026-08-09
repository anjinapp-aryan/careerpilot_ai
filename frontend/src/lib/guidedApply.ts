import { api } from '@/lib/api';
import type { GuidedApplyBrief } from '@/types/workflow';

/**
 * Guided Apply — "CareerPilot prepares" projection for one application. Live, no dark flag (same
 * as `GET /api/applications/cards`): candidate profile facts + recommended answers, resolved
 * server-side via the same `AnswerResolver`/`FieldMappingService` the browser form engine uses.
 */
export const guidedApply = {
  brief: async (applicationId: string): Promise<GuidedApplyBrief | null> => {
    try {
      return (await api.get(`/api/applications/${applicationId}/guided-apply-brief`)).data;
    } catch {
      return null;
    }
  },
};
