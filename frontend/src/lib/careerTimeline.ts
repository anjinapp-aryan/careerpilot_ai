import { api } from '@/lib/api';

/**
 * Phase 10H — Career Timeline API client. GET /api/career-timeline always returns 200 (never
 * 404-on-dark, unlike most other feature APIs in this codebase) with an explicit `enabled` flag —
 * this lets the UI distinguish "feature is off" from "no events yet" instead of collapsing both
 * into the same empty state.
 */

export type CareerTimelineCategory =
  | 'MISSION'
  | 'APPLICATION'
  | 'RESUME'
  | 'LEARNING'
  | 'INTERVIEW'
  | 'MEMORY'
  | 'COMPANY'
  | 'WORKFLOW';

export interface CareerTimelineEntry {
  id: string;
  category: CareerTimelineCategory;
  eventType: string;
  title: string;
  description?: string | null;
  occurredAt: string;
  source?: string | null;
  aiGenerated?: boolean | null;
  relatedMissionId?: string | null;
  relatedJobId?: string | null;
  relatedCompanyId?: string | null;
  relatedCompanyName?: string | null;
}

export interface CareerTimelineResponse {
  entries: CareerTimelineEntry[];
  hasMore: boolean;
  enabled: boolean;
}

export const careerTimeline = {
  get: (params: { category?: CareerTimelineCategory | null; page?: number; size?: number }) =>
    api
      .get<CareerTimelineResponse>('/api/career-timeline', {
        params: {
          category: params.category ?? undefined,
          page: params.page ?? 0,
          size: params.size ?? 25,
        },
      })
      .then((r) => r.data),
};
