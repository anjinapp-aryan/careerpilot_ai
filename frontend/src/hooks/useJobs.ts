import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { Job, JobsPage } from '@/types/workflow';

export const jobsQueryKey = ['jobs'] as const;

/**
 * Fetches jobs for selection.
 * GET /api/jobs -> { content: Job[] }
 *
 * Returns the unwrapped `content` array so consumers work with `Job[]`
 * directly. Search/filtering is performed client-side in the multi-select.
 */
export function useJobs(): UseQueryResult<Job[]> {
  return useQuery({
    queryKey: jobsQueryKey,
    queryFn: async ({ signal }) => {
      const { data } = await api.get<JobsPage>('/api/jobs', { signal });
      return data?.content ?? [];
    },
    staleTime: 30_000,
  });
}
