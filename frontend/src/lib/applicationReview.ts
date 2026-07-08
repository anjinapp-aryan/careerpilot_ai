import { api } from '@/lib/api';

/**
 * Phase 7.12 — AI Review Pipeline API client. Thin typed wrappers over the additive
 * /api/application-review/* surface. Dark by default: reads 404 until the pipeline is enabled, so
 * callers must tolerate absent data (never fabricate a review client-side). POST /run reviews only —
 * it never submits an application.
 */

export type ReviewVerdict = 'READY' | 'HUMAN_REVIEW' | 'BLOCKED';
export type ConsistencyStatus = 'PASS' | 'WARNING' | 'FAIL';
export type QualityCategory = 'EXCELLENT' | 'STRONG' | 'GOOD' | 'WEAK' | 'BLOCKED';

export interface ApplicationReviewResponse {
  id: string;
  applicationPackageId: string;
  userId: string;
  jobId: string;
  packageVersion: number;
  resumeScore: number | null;
  atsScore: number | null;
  companyFitScore: number | null;
  learningConfidence: number | null;
  consistencyStatus: ConsistencyStatus | null;
  qualityScore: number | null;
  qualityCategory: QualityCategory | null;
  verdict: ReviewVerdict;
  confidence: number | null;
  reasons: string | null;
  correlationId: string | null;
  reviewVersion: number;
  createdAt: string;
  updatedAt: string;
}

export interface ReviewHistoryResponse {
  id: string;
  applicationReviewId: string;
  packageVersion: number;
  reviewVersion: number;
  qualityScore: number | null;
  qualityCategory: QualityCategory | null;
  verdict: ReviewVerdict;
  confidence: number | null;
  consistencyStatus: ConsistencyStatus | null;
  createdAt: string;
}

export interface ReviewQualityResponse {
  applicationPackageId: string;
  qualityScore: number | null;
  qualityCategory: QualityCategory | null;
  verdict: ReviewVerdict;
  confidence: number | null;
}

export async function getReview(packageId: string) {
  return (await api.get<ApplicationReviewResponse>(`/api/application-review/${packageId}`)).data;
}

export async function getReviewHistory(packageId: string) {
  return (await api.get<ReviewHistoryResponse[]>(`/api/application-review/${packageId}/history`)).data;
}

export async function getReviewQuality(packageId: string) {
  return (await api.get<ReviewQualityResponse>(`/api/application-review/${packageId}/quality`)).data;
}

/** Run the review pipeline over a package. Review only — never submits. */
export async function runReview(packageId: string) {
  return (await api.post<ApplicationReviewResponse>(`/api/application-review/${packageId}/run`)).data;
}

/** One parsed reviewer entry from the review's reasons JSON. */
export interface ReviewerReason {
  reviewer: string;
  score?: number | null;
  status?: string;
  category?: string;
  reasons?: string[];
}

export function parseReasons(reasons: string | null | undefined): ReviewerReason[] {
  if (!reasons) return [];
  try {
    return JSON.parse(reasons) as ReviewerReason[];
  } catch {
    return [];
  }
}
