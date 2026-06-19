import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { Resume } from '@/types/workflow';

export const resumesQueryKey = ['resumes'] as const;

/**
 * Fetches the current user's resumes.
 * GET /api/resumes -> Resume[]
 */
export function useResumes(): UseQueryResult<Resume[]> {
  return useQuery({
    queryKey: resumesQueryKey,
    queryFn: async ({ signal }) => {
      const { data } = await api.get<Resume[]>('/api/resumes', { signal });
      return data;
    },
    staleTime: 30_000,
  });
}
