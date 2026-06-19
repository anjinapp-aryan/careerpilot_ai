import {
  useMutation,
  useQueryClient,
  type UseMutationResult,
} from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { StartWorkflowPayload, WorkflowRun } from '@/types/workflow';

/**
 * Starts a new AI workflow run.
 * POST /api/workflows/run  body: { resumeId, jobIds, ...extra }
 *
 * On success, invalidates the workflow + dashboard caches so dependent
 * views refetch. Additional backend fields (targetRole, etc.) can be merged
 * into the payload by the caller thanks to StartWorkflowPayload's index sig.
 */
export function useStartWorkflow(): UseMutationResult<
  WorkflowRun,
  unknown,
  StartWorkflowPayload
> {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (payload: StartWorkflowPayload) => {
      const { data } = await api.post<WorkflowRun>(
        '/api/workflows/run',
        payload,
      );
      return data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workflows'] });
      queryClient.invalidateQueries({ queryKey: ['dashboard'] });
    },
  });
}

/**
 * Normalizes an unknown error (axios or otherwise) into a display string.
 * Kept here so form/UI code stays free of axios-specific shapes.
 */
export function toErrorMessage(error: unknown, fallback = 'Something went wrong'): string {
  if (typeof error === 'object' && error !== null) {
    const maybe = error as {
      response?: { data?: { message?: unknown } };
      message?: unknown;
    };
    const apiMessage = maybe.response?.data?.message;
    if (typeof apiMessage === 'string' && apiMessage.trim()) return apiMessage;
    if (typeof maybe.message === 'string' && maybe.message.trim()) return maybe.message;
  }
  return fallback;
}
