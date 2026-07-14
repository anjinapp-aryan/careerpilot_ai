import { api } from '@/lib/api';

/**
 * Phase 7.15 — STAR Story Intelligence API client. Every endpoint ships DARK on the backend
 * (story.engine.enabled=false ⇒ 404, mutating endpoints ⇒ 409), so every fetcher here resolves to
 * null/[] instead of throwing; UI built on these renders nothing while the feature is dark. Mirrors
 * `lib/companyIntel.ts`'s `nullOn404` pattern exactly.
 */

export type StoryType =
  | 'LEADERSHIP' | 'OWNERSHIP' | 'CONFLICT_RESOLUTION' | 'FAILURE' | 'SUCCESS' | 'INNOVATION'
  | 'CUSTOMER_OBSESSION' | 'MENTORING' | 'ARCHITECTURE' | 'SYSTEM_DESIGN' | 'PRODUCTION_INCIDENT'
  | 'PERFORMANCE_OPTIMIZATION' | 'SCALABILITY' | 'MICROSERVICES' | 'CLOUD_MIGRATION' | 'SECURITY'
  | 'DEVOPS' | 'AUTOMATION' | 'DELIVERY' | 'AGILE' | 'COMMUNICATION' | 'STAKEHOLDER_MANAGEMENT'
  | 'CROSS_TEAM_COLLABORATION' | 'TECHNICAL_DECISION' | 'PROBLEM_SOLVING' | 'CAREER_GROWTH'
  | 'PROMOTION' | 'RECOGNITION';

export type StoryStatus = 'DRAFT' | 'COMPLETE' | 'ARCHIVED';

export interface StorySummary {
  id: string;
  title: string;
  storyType: StoryType;
  status: StoryStatus;
  qualityScore?: number | null;
  updatedAt?: string | null;
}

export interface StoryResponse extends StorySummary {
  source: string;
  situation?: string | null;
  task?: string | null;
  action?: string | null;
  result?: string | null;
  reflection?: string | null;
  lessonsLearned?: string | null;
  skillsUsed?: string | null;
  technologiesUsed?: string | null;
  competencies?: string | null;
  businessImpact?: string | null;
  evidence?: string | null;
  confidenceScore?: number | null;
  qualityBreakdown?: string | null;
  improvementSuggestions?: string | null;
  missingSections?: string | null;
  currentVersion: number;
  createdAt?: string | null;
}

export interface StoryAnalytics {
  totalStories?: number | null;
  avgQualityScore?: number | null;
  byCategory?: string | null;
  byCompetency?: string | null;
  usageCount?: number | null;
  recommendationCount?: number | null;
  lastComputedAt?: string | null;
}

export interface StoryRecommendation {
  id: string;
  starStoryId: string;
  storyTitle?: string | null;
  companyName?: string | null;
  targetRole?: string | null;
  question?: string | null;
  matchScore?: number | null;
  reason?: string | null;
}

async function nullOn404<T>(req: Promise<{ data: T }>): Promise<T | null> {
  try {
    return (await req).data;
  } catch (err: unknown) {
    const status = (err as { response?: { status?: number } })?.response?.status;
    if (status === 404 || status === 403 || status === 409) return null;
    throw err;
  }
}

export const storyIntel = {
  list: () => nullOn404<StorySummary[]>(api.get('/api/story')),
  get: (id: string) => nullOn404<StoryResponse>(api.get(`/api/story/${id}`)),
  categories: () => nullOn404<StoryType[]>(api.get('/api/story/categories')),
  analytics: () => nullOn404<StoryAnalytics>(api.get('/api/story/analytics')),
  search: (q?: string) => nullOn404<StorySummary[]>(api.get('/api/story/search', { params: q ? { q } : {} })),
  generate: (payload: { storyType?: StoryType; title?: string; hint?: string }) =>
    nullOn404<StoryResponse>(api.post('/api/story/generate', payload)),
  improve: (id: string, feedback?: string) =>
    nullOn404<StoryResponse>(api.post(`/api/story/${id}/improve`, { feedback })),
  rate: (id: string, confidenceScore?: number) =>
    nullOn404<StoryResponse>(api.post(`/api/story/${id}/rate`, { confidenceScore })),
  recommend: (payload: { companyName?: string; targetRole?: string; question?: string }) =>
    nullOn404<StoryRecommendation[]>(api.post('/api/story/recommend', payload)),
};
