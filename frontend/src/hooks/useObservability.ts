import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { BadgeTone } from '@/components/ui/badge';

export interface ObservabilitySnapshot {
  overall?: string;
  workflow?: { health?: string } & Record<string, unknown>;
  execution?: { health?: string } & Record<string, unknown>;
  providers?: { health?: string; providers?: Record<string, string> } & Record<string, unknown>;
  [key: string]: unknown;
}

/**
 * Phase 10E — single shared source of truth for `GET /api/diagnostics/observability`.
 * Previously fetched independently by Dashboard.tsx, Workflow.tsx (StageDiagnostics),
 * and MissionDashboard.tsx, each with its own query key — so the three pages never
 * shared a cache entry even though they read the exact same payload. All three
 * already used `retry: false`; the global QueryClient default (`main.tsx`) already
 * sets `staleTime: 30_000`, so this hook's explicit `staleTime` matches what every
 * caller already had — no behavior change, only a shared cache key.
 *
 * `refetchInterval` is opt-in per caller (MissionDashboard's small always-visible
 * status badge polls every 30s; Dashboard/Workflow just want a one-shot snapshot).
 */
export function useObservability<T = ObservabilitySnapshot>(refetchInterval?: number): UseQueryResult<T> {
  return useQuery<T>({
    queryKey: ['observability'],
    queryFn: async () => (await api.get('/api/diagnostics/observability')).data,
    retry: false,
    staleTime: 30_000,
    refetchInterval,
  });
}

const HEALTH_TONE: Record<string, BadgeTone> = {
  UP: 'success',
  HEALTHY: 'success',
  DEGRADED: 'warning',
  DOWN: 'danger',
  NOT_CONFIGURED: 'neutral',
  UNKNOWN: 'neutral',
};

/** Shared health-string → badge tone, previously duplicated (with narrower value sets) across Dashboard.tsx, Workflow.tsx, and MissionDashboard.tsx. */
export function healthTone(v?: string): BadgeTone {
  return HEALTH_TONE[(v ?? '').toUpperCase()] ?? 'neutral';
}
