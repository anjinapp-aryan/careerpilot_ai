import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import {
  AlertTriangle,
  ArrowLeft,
  BadgeCheck,
  CheckCircle2,
  Clock,
  FileText,
  History,
  Loader2,
  RefreshCw,
  Sparkles,
  XCircle,
} from 'lucide-react';
import { api } from '@/lib/api';
import { PageHeader } from '@/components/common/PageHeader';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { useToast } from '@/components/ui/toast';
import { Dialog, DialogBody, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import type { CandidateProfile, ResumeAnalysisHistoryEntry, ResumeAnalysisStatus } from '@/types/workflow';

const STATUS_META: Record<ResumeAnalysisStatus['status'], { label: string; tone: BadgeTone; icon: typeof CheckCircle2 }> = {
  NOT_ANALYZED: { label: 'Not analyzed', tone: 'neutral', icon: FileText },
  ANALYZING: { label: 'Analyzing…', tone: 'primary', icon: Loader2 },
  ANALYZED: { label: 'Analyzed', tone: 'success', icon: CheckCircle2 },
  OUTDATED: { label: 'Outdated', tone: 'warning', icon: Clock },
  FAILED: { label: 'Failed', tone: 'danger', icon: XCircle },
  PARTIAL: { label: 'Partial', tone: 'warning', icon: AlertTriangle },
};

/**
 * Phase 8.2 — Resume Intelligence Center. Pure UI + orchestration over the existing
 * CandidateProfileService pipeline (via ResumeIntelligenceCenterService) — no new extraction
 * logic lives here. Dark-tolerant: every call 404s quietly when RESUME_INTELLIGENCE_CENTER_ENABLED
 * is off, rendering an explanatory empty state rather than an error.
 */
export default function ResumeIntelligence() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { toast } = useToast();
  const [showHistory, setShowHistory] = useState(false);

  const statusQuery = useQuery<ResumeAnalysisStatus | null>({
    queryKey: ['resume-intelligence', 'status', id],
    queryFn: async () => {
      try {
        return (await api.get(`/api/resumes/${id}/status`)).data;
      } catch {
        return null; // feature off, or resume not owned by this user
      }
    },
    enabled: !!id,
    retry: false,
  });

  const status = statusQuery.data;
  const isAnalyzedLike = status?.status === 'ANALYZED' || status?.status === 'PARTIAL';

  const analysisQuery = useQuery<CandidateProfile | null>({
    queryKey: ['resume-intelligence', 'analysis', id],
    queryFn: async () => {
      try {
        return (await api.get(`/api/resumes/${id}/analysis`)).data;
      } catch {
        return null;
      }
    },
    enabled: !!id && isAnalyzedLike,
    retry: false,
  });

  const analyze = useMutation({
    mutationFn: async () => (await api.post(`/api/resumes/${id}/analyze`)).data as ResumeAnalysisStatus,
    onSuccess: (result) => {
      qc.invalidateQueries({ queryKey: ['resume-intelligence', 'status', id] });
      qc.invalidateQueries({ queryKey: ['resume-intelligence', 'analysis', id] });
      qc.invalidateQueries({ queryKey: ['resume-intelligence', 'dashboard'] });
      if (result.status === 'FAILED') {
        toast({ variant: 'error', title: 'Analysis failed', description: result.errorMessage ?? 'Please try again.' });
      } else {
        toast({ variant: 'success', title: 'Resume analyzed' });
      }
    },
    onError: () => toast({ variant: 'error', title: 'Could not analyze resume' }),
  });

  if (statusQuery.isLoading) {
    return (
      <div className="space-y-6">
        <Skeleton className="h-12 w-full" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  if (!status) {
    return (
      <EmptyState
        icon={Sparkles}
        title="Resume Intelligence Center isn't available"
        description="This feature isn't enabled yet, or this resume couldn't be found."
        action={
          <Button variant="outline" onClick={() => navigate('/resumes')}>
            <ArrowLeft className="h-4 w-4" /> Back to resumes
          </Button>
        }
      />
    );
  }

  const meta = STATUS_META[status.status];
  const StatusIcon = meta.icon;
  const profile = analysisQuery.data;
  const confidencePct = status.confidenceScore != null ? Math.round(status.confidenceScore * 100) : null;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Resume Intelligence"
        description="AI-extracted intelligence for this resume — skills, experience, ATS readiness, and how it's changed over time."
        actions={
          <div className="flex items-center gap-2">
            <Button variant="ghost" onClick={() => navigate('/resumes')}>
              <ArrowLeft className="h-4 w-4" /> Back
            </Button>
            <Button variant="outline" onClick={() => setShowHistory(true)}>
              <History className="h-4 w-4" /> History
            </Button>
            <Button onClick={() => analyze.mutate()} loading={analyze.isPending}>
              {!analyze.isPending && <RefreshCw className="h-4 w-4" />}
              {status.status === 'NOT_ANALYZED' ? 'Analyze resume' : 'Re-analyze'}
            </Button>
          </div>
        }
      />

      <Card className="p-5">
        <div className="flex flex-wrap items-center gap-3">
          <Badge tone={meta.tone}>
            <StatusIcon className={`h-3.5 w-3.5 ${status.status === 'ANALYZING' ? 'animate-spin' : ''}`} /> {meta.label}
          </Badge>
          {confidencePct != null && (
            <Badge tone="neutral">
              <BadgeCheck className="h-3.5 w-3.5" /> {confidencePct}% confidence
            </Badge>
          )}
          <Badge tone={status.atsScore != null ? (status.atsScore >= 75 ? 'success' : 'warning') : 'neutral'}>
            ATS {status.atsScore ?? 'Not scored yet'}
          </Badge>
          {status.lastAnalysisDate && (
            <span className="text-xs text-muted-foreground">
              Last analyzed {new Date(status.lastAnalysisDate).toLocaleString()}
              {status.analysisDurationMs != null ? ` · ${(status.analysisDurationMs / 1000).toFixed(1)}s` : ''}
            </span>
          )}
        </div>

        {status.status === 'OUTDATED' && (
          <p className="mt-3 text-sm text-warning">
            A newer resume has been analyzed since this one — this analysis is no longer your current profile.
            Re-analyze to make this resume the active one again.
          </p>
        )}
        {status.status === 'FAILED' && status.errorMessage && (
          <p className="mt-3 text-sm text-danger">{status.errorMessage}</p>
        )}
        {status.status === 'PARTIAL' && (
          <p className="mt-3 text-sm text-warning">
            Analysis completed with low confidence — the extracted sections below may be incomplete. Consider
            re-analyzing, or check that the uploaded file has readable text.
          </p>
        )}
      </Card>

      {status.status === 'NOT_ANALYZED' && (
        <EmptyState
          icon={Sparkles}
          title="This resume hasn't been analyzed yet"
          description="Run analysis to extract skills, experience, technologies, and more."
          action={
            <Button onClick={() => analyze.mutate()} loading={analyze.isPending}>
              <Sparkles className="h-4 w-4" /> Analyze resume
            </Button>
          }
        />
      )}

      {isAnalyzedLike && (
        analysisQuery.isLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : profile ? (
          <ProfileSections profile={profile} />
        ) : null
      )}

      <ResumeHistoryDialog resumeId={id!} open={showHistory} onClose={() => setShowHistory(false)} />
    </div>
  );
}

function ProfileSections({ profile }: { profile: CandidateProfile }) {
  return (
    <Card className="p-5">
      <h2 className="text-sm font-semibold text-foreground">Extracted intelligence</h2>
      {profile.profileSummary && <p className="mt-2 text-sm text-muted-foreground">{profile.profileSummary}</p>}
      <div className="mt-3 grid gap-4 sm:grid-cols-3">
        <Stat label="Experience" value={profile.yearsExperience != null ? `${profile.yearsExperience} yrs` : '—'} />
        <Stat label="Current role" value={profile.currentRole ?? '—'} />
        <Stat label="Seniority" value={profile.seniority ?? '—'} />
      </div>
      <ChipRow label="Target roles" items={profile.targetRoles} />
      <ChipRow label="Skills" items={profile.skills} />
      <ChipRow label="Technologies" items={profile.technologies ?? []} />
      <ChipRow label="Certifications" items={profile.certifications ?? []} />
      <ChipRow label="Industries" items={profile.industries ?? []} />
      <ChipRow label="Domains" items={profile.domains} />
      <ChipRow label="Career goals" items={profile.careerGoals ?? []} />
      <ChipRow label="Preferred locations" items={[...profile.preferredCountries, ...profile.preferredCities]} />
      {(profile.leadershipExperience || profile.cloudExpertise) && (
        <div className="mt-3 flex flex-wrap gap-1.5">
          {profile.leadershipExperience && <Badge tone="neutral">Leadership experience</Badge>}
          {profile.cloudExpertise && <Badge tone="neutral">Cloud expertise</Badge>}
        </div>
      )}
    </Card>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="text-sm font-medium text-foreground">{value}</p>
    </div>
  );
}

function ChipRow({ label, items }: { label: string; items: string[] }) {
  if (!items || items.length === 0) return null;
  return (
    <div className="mt-3">
      <span className="text-xs font-medium text-muted-foreground">{label}</span>
      <div className="mt-1.5 flex flex-wrap gap-1.5">
        {items.map((it) => (
          <Badge key={it} tone="primary">
            {it}
          </Badge>
        ))}
      </div>
    </div>
  );
}

/** This resume's slice of the profile's version history — see ResumeIntelligenceCenterService.history(). */
function ResumeHistoryDialog({ resumeId, open, onClose }: { resumeId: string; open: boolean; onClose: () => void }) {
  const { data, isLoading } = useQuery<ResumeAnalysisHistoryEntry[]>({
    queryKey: ['resume-intelligence', 'history', resumeId],
    queryFn: async () => {
      try {
        return (await api.get(`/api/resumes/${resumeId}/history`)).data;
      } catch {
        return [];
      }
    },
    enabled: open,
    retry: false,
    staleTime: 30_000,
  });
  const entries = data ?? [];

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()} size="lg">
      <DialogHeader onClose={onClose}>
        <DialogTitle>
          <span className="flex items-center gap-2">
            <History className="h-4 w-4 text-primary" /> Analysis history
          </span>
        </DialogTitle>
        <DialogDescription>Every analysis run involving this resume, newest first.</DialogDescription>
      </DialogHeader>
      <DialogBody className="space-y-4">
        {isLoading ? (
          <Skeleton className="h-32 w-full" />
        ) : entries.length === 0 ? (
          <p className="text-sm text-muted-foreground">No analysis history yet for this resume.</p>
        ) : (
          <ol className="space-y-4 border-l border-border pl-4">
            {entries.map((e, i) => (
              <li key={i} className="relative">
                <span className="absolute -left-[21px] top-1 h-2 w-2 rounded-full bg-primary" />
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone="primary" className="text-[10px]">{e.reason}</Badge>
                  <span className="text-xs text-muted-foreground">{new Date(e.createdAt).toLocaleString()}</span>
                </div>
                {e.after && (
                  <div className="mt-1.5 text-xs text-muted-foreground">
                    <span className="font-medium text-foreground">{e.after.currentRole ?? '—'}</span>
                    {e.after.seniority ? ` · ${e.after.seniority}` : ''}
                    {` · ${e.after.skills?.length ?? 0} skills`}
                  </div>
                )}
              </li>
            ))}
          </ol>
        )}
      </DialogBody>
    </Dialog>
  );
}
