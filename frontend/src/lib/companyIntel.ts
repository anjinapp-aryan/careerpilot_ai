import { api } from '@/lib/api';

/**
 * Phase 7.13 — Company Intelligence API client. Every endpoint ships DARK on the backend
 * (company.knowledge.enabled=false ⇒ 404), so every fetcher here resolves to null/[] on 404
 * instead of throwing; UI built on these renders nothing while the feature is dark.
 */

export interface CompanyScores {
  qualityScore?: number | null;
  interviewDifficulty?: number | null;
  hiringProbability?: number | null;
  resumeCompatibility?: number | null;
  technologyMatch?: number | null;
  careerGrowth?: number | null;
  salaryPotential?: number | null;
  cultureMatch?: number | null;
  learningValue?: number | null;
  longTermOpportunity?: number | null;
}

export interface CompanySummary {
  id: string;
  companyName: string;
  industry?: string | null;
  qualityScore?: number | null;
  hiringProbability?: number | null;
  knowledgeVersion: number;
  updatedAt?: string | null;
}

export interface CompanyInsight {
  kind: string;
  headline: string;
  detail: string;
}

export interface CompanyResponse {
  id: string;
  companyName: string;
  industry?: string | null;
  knowledgeVersion: number;
  lastSource?: string | null;
  firstDiscoveredAt?: string | null;
  updatedAt?: string | null;
  scores: CompanyScores;
  scoreExplanations: Record<string, string>;
  knowledge: Record<string, string>;
  insights: CompanyInsight[];
}

export interface CompanyTimelineEvent {
  id: string;
  eventType: string;
  refId?: string | null;
  detail?: string | null;
  occurredAt: string;
}

export interface CompanyStatistics {
  companies: number;
  knowledgeVersions: number;
  timelineEvents: number;
  graphEdges: number;
  topCompanies: CompanySummary[];
  technologyDistribution: Record<string, number>;
  industryDistribution: Record<string, number>;
}

/**
 * Phase 7.17 — Candidate Fit unification. Every `*Fit`/`memoryInfluence`/`overallFit` value is
 * `null` (never a guessed number) when its backing source is missing. leadership/architecture/
 * domain/cloudFit are ALWAYS null — no data source for those exists anywhere in the platform yet.
 */
export interface CandidateFit {
  overallFit?: number | null; overallFitExplanation: string;
  skillFit?: number | null; skillFitExplanation: string;
  experienceFit?: number | null; experienceFitExplanation: string;
  locationFit?: number | null; locationFitExplanation: string;
  salaryFit?: number | null; salaryFitExplanation: string;
  visaFit?: number | null; visaFitExplanation: string;
  technologyFit?: number | null; technologyFitExplanation: string;
  careerGoalFit?: number | null; careerGoalFitExplanation: string;
  leadershipFit?: number | null; leadershipFitExplanation: string;
  architectureFit?: number | null; architectureFitExplanation: string;
  domainFit?: number | null; domainFitExplanation: string;
  cloudFit?: number | null; cloudFitExplanation: string;
  memoryInfluence?: number | null; memoryInfluenceExplanation: string;
  sources: { jobRecommendation: boolean; companyKnowledge: boolean };
}

/** Phase 7.17.2 — real Interview/InterviewFeedback data aggregated per company. Every honesty note applies verbatim. */
export interface CompanyInterviewIntelligence {
  companyName: string;
  interviewRounds: number;
  roundsByType?: Record<string, number>;
  behavioralRoundsNote?: string;
  codingRoundsNote?: string;
  passRate?: number | null;
  passRateEvidence?: string;
  passRateNote?: string;
  avgDurationMinutes?: number | null;
  avgDurationEvidence?: string;
  avgFeedbackRating?: number | null;
  positiveFeedbackCount?: number;
  negativeFeedbackCount?: number;
  feedbackEvidence?: string;
  frequentlyMentionedTechnologies?: string[];
  frequentlyMentionedTechnologiesEvidence?: string;
  confidence: 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH';
  confidenceEvidence?: string;
  sampleSize?: number;
}

/** Phase 7.17.3 — extends existing HiringPatternAnalyzer/CompanyTrendAnalyzer output with time-to-hire. */
export interface CompanyHiringIntelligence {
  avgTimeToHireDays?: number | null;
  avgTimeToHireEvidence?: string;
  departmentHiring?: null;
  departmentHiringNote?: string;
  seasonalHiring?: null;
  seasonalHiringNote?: string;
  hiringProbability?: number | null;
  insights: CompanyInsight[];
}

/** Phase 7.17.4 — deterministic keyword-taxonomy bucketing of existing tech/skill graph edges. */
export interface CompanyTechnologyTaxonomy {
  byCategory: Record<string, { target: string; weight: number }[]>;
  totalEdges: number;
  growthDeclineNote: string;
}

async function nullOn404<T>(req: Promise<{ data: T }>): Promise<T | null> {
  try {
    return (await req).data;
  } catch (err: unknown) {
    const status = (err as { response?: { status?: number } })?.response?.status;
    if (status === 404 || status === 403) return null; // dark flag or unknown company
    throw err;
  }
}

export const companyIntel = {
  search: (q?: string) =>
    nullOn404<CompanySummary[]>(api.get('/api/company/search', { params: q ? { q } : {} })),
  get: (id: string) => nullOn404<CompanyResponse>(api.get(`/api/company/${id}`)),
  timeline: (id: string) => nullOn404<CompanyTimelineEvent[]>(api.get(`/api/company/${id}/timeline`)),
  statistics: () => nullOn404<CompanyStatistics>(api.get('/api/company/statistics')),
  /** Phase 7.17 — unified job+company fit explanation. Null when dark or the job doesn't exist. */
  fit: (jobId: string) => nullOn404<CandidateFit>(api.get('/api/company/fit', { params: { jobId } })),
  interviewIntelligence: (id: string) =>
    nullOn404<CompanyInterviewIntelligence>(api.get(`/api/company/${id}/interview-intelligence`)),
  hiringIntelligence: (id: string) =>
    nullOn404<CompanyHiringIntelligence>(api.get(`/api/company/${id}/hiring-intelligence`)),
  technologyTaxonomy: (id: string) =>
    nullOn404<CompanyTechnologyTaxonomy>(api.get(`/api/company/${id}/technology-taxonomy`)),
  compare: (a: string, b: string) =>
    nullOn404<{ a: CompanySummary; b: CompanySummary; similarity: number; knowledgeDiff: Record<string, (string | null)[]> }>(
      api.get('/api/company/compare', { params: { a, b } }),
    ),
  /** Resolve a company by display name via search; null when dark or unknown. */
  findByName: async (name?: string | null): Promise<CompanySummary | null> => {
    if (!name?.trim()) return null;
    const results = await companyIntel.search(name.trim());
    return results?.[0] ?? null;
  },
};
