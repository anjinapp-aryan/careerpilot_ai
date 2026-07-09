import { api } from '@/lib/api';

/**
 * Phase 7.11 — Application Package Intelligence API client. Thin typed wrappers over the additive
 * /api/application-package/* surface. All endpoints are dark by default; reads 404 until the layer
 * is enabled, so callers must tolerate absent data (never fabricate a package client-side).
 */

export type PackageValidationStatus = 'READY' | 'HUMAN_REVIEW' | 'BLOCKED';

export interface ApplicationPackageResponse {
  id: string;
  userId: string;
  jobId: string;
  applicationId: string | null;
  resumeId: string | null;
  resumeTailoringId: string | null;
  coverLetterId: string | null;
  atsAnalysisId: string | null;
  gapAnalysisId: string | null;
  atsExplanationId: string | null;
  recommendationAuditId: string | null;
  applicationDecisionId: string | null;
  companyResearchAvailable: boolean | null;
  learningBoost: number | null;
  recommendationStrength: string | null;
  matchSummary: string | null;
  correlationId: string | null;
  packageVersion: number;
  status: string;
  validationStatus: PackageValidationStatus | null;
  metadata: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PackageVersionResponse {
  id: string;
  applicationPackageId: string;
  packageVersion: number;
  status: string;
  resumeId: string | null;
  resumeTailoringId: string | null;
  atsAnalysisId: string | null;
  metadata: string | null;
  createdAt: string;
}

export interface PackageValidationResponse {
  id: string;
  applicationPackageId: string;
  packageVersion: number;
  status: PackageValidationStatus;
  blockingReason: string | null;
  checks: string | null;
  correlationId: string | null;
  createdAt: string;
}

export interface PackageCompareResponse {
  current: ApplicationPackageResponse;
  against: PackageVersionResponse;
  changedArtifacts: string[];
}

export async function getPackage(id: string) {
  return (await api.get<ApplicationPackageResponse>(`/api/application-package/${id}`)).data;
}

export async function getPackageHistory(id: string) {
  return (await api.get<PackageVersionResponse[]>(`/api/application-package/${id}/history`)).data;
}

export async function getPackageValidation(id: string) {
  return (await api.get<PackageValidationResponse>(`/api/application-package/${id}/validation`)).data;
}

export async function comparePackage(id: string, version: number) {
  return (await api.get<PackageCompareResponse>(`/api/application-package/${id}/compare/${version}`)).data;
}

/** Assemble (never submit) a package for a job. */
export async function generatePackage(jobId: string) {
  return (await api.post<ApplicationPackageResponse>(`/api/application-package/${jobId}/generate`)).data;
}

/** Parsed validation gate. */
export interface PackageCheck {
  name: string;
  passed: boolean;
  severity: 'BLOCK' | 'REVIEW';
}

export function parseChecks(checks: string | null | undefined): PackageCheck[] {
  if (!checks) return [];
  try {
    return JSON.parse(checks) as PackageCheck[];
  } catch {
    return [];
  }
}
