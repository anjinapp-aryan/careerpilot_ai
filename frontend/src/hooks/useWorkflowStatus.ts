import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { WorkflowStatusDetail } from '@/types/workflow';

// Statuses where nothing changes until the user acts (and that action invalidates
// the query): a finished run, a failed run, a rejected run, or one parked at the
// human-approval gate. Polling these is wasted work — stop it.
const TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED', 'REJECTED', 'INTERRUPTED']);

export function useWorkflowStatus(threadId: string | null | undefined) {
  return useQuery<WorkflowStatusDetail>({
    queryKey: ['workflow-status', threadId],
    queryFn: () => api.get(`/api/workflows/${threadId}`).then((r) => r.data),
    enabled: !!threadId,
    staleTime: 0,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status && TERMINAL_STATUSES.has(status) ? false : 5_000;
    },
  });
}
