import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Sparkles, RefreshCw, BadgeCheck, History } from 'lucide-react';
import { api } from '@/lib/api';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { useToast } from '@/components/ui/toast';
import { Dialog, DialogBody, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import type { CandidateProfile, CandidateProfileHistoryEntry } from '@/types/workflow';

/**
 * Read-only summary of the canonical Candidate Intelligence Profile (Phase 1).
 * Ships dark: when the feature flag is off or no profile exists yet, the API returns 404 and
 * this component renders nothing — so it never affects the Recommended tab when disabled.
 */
export function CandidateProfileCard() {
  const qc = useQueryClient();
  const { toast } = useToast();
  const [showHistory, setShowHistory] = useState(false);

  const { data: profile, isLoading } = useQuery<CandidateProfile | null>({
    queryKey: ['candidate', 'profile'],
    queryFn: async () => {
      try {
        return (await api.get('/api/candidate-profile')).data as CandidateProfile;
      } catch {
        return null; // 404 = feature off or no profile yet
      }
    },
    retry: false,
  });

  const rebuild = useMutation({
    mutationFn: async () => (await api.post('/api/candidate-profile/rebuild')).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['candidate', 'profile'] });
      toast({ variant: 'success', title: 'Profile rebuilt', description: 'Your intelligence profile was refreshed.' });
    },
    onError: () => toast({ variant: 'error', title: 'Could not rebuild profile' }),
  });

  if (isLoading || !profile) return null;

  const confidencePct =
    profile.confidenceScore != null ? Math.round(profile.confidenceScore * 100) : null;

  return (
    <Card className="p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="flex items-center gap-2 text-sm font-semibold text-foreground">
            <Sparkles className="h-4 w-4 text-primary" /> Your Intelligence Profile
          </h2>
          <p className="mt-1 text-sm text-muted-foreground">
            {[profile.currentRole, profile.seniority].filter(Boolean).join(' · ') || 'Profile in progress'}
            {profile.yearsExperience != null ? ` · ${profile.yearsExperience}+ yrs` : ''}
          </p>
        </div>
        <div className="flex items-center gap-2">
          {confidencePct != null && (
            <Badge tone="neutral">
              <BadgeCheck className="h-3.5 w-3.5" /> {confidencePct}% confidence
            </Badge>
          )}
          <Button size="sm" variant="ghost" onClick={() => setShowHistory(true)}>
            <History className="h-3.5 w-3.5" /> History
          </Button>
          <Button size="sm" variant="outline" onClick={() => rebuild.mutate()} loading={rebuild.isPending}>
            <RefreshCw className="h-3.5 w-3.5" /> Rebuild
          </Button>
        </div>
      </div>

      {profile.profileSummary && (
        <p className="mt-3 text-sm text-muted-foreground">{profile.profileSummary}</p>
      )}

      <ChipRow label="Target roles" items={profile.targetRoles} />
      <ChipRow label="Top skills" items={profile.skills.slice(0, 12)} />
      <ChipRow label="Technologies" items={profile.technologies?.slice(0, 12) ?? []} />
      <ChipRow label="Certifications" items={profile.certifications ?? []} />
      <ChipRow label="Industries" items={profile.industries ?? []} />
      <ChipRow label="Preferred locations" items={[...profile.preferredCountries, ...profile.preferredCities]} />
      <ChipRow label="Work modes" items={profile.workModes} />
      <ChipRow label="Career goals" items={profile.careerGoals ?? []} />
      {(profile.excludedRoles ?? []).length > 0 && (
        <div className="mt-3">
          <span className="text-xs font-medium text-muted-foreground">Excluded roles</span>
          <div className="mt-1.5 flex flex-wrap gap-1.5">
            {profile.excludedRoles.map((r) => (
              <Badge key={r} tone="danger">✗ {r}</Badge>
            ))}
          </div>
        </div>
      )}
      {(profile.leadershipExperience || profile.cloudExpertise) && (
        <div className="mt-3 flex flex-wrap gap-1.5">
          {profile.leadershipExperience && <Badge tone="neutral">Leadership experience</Badge>}
          {profile.cloudExpertise && <Badge tone="neutral">Cloud expertise</Badge>}
        </div>
      )}

      <ProfileHistoryDialog open={showHistory} onClose={() => setShowHistory(false)} />
    </Card>
  );
}

/**
 * Profile evolution — the before/after audit trail from GET /api/candidate-profile/history.
 * Fetched only while the dialog is open; 404 (feature dark / no history) renders a quiet
 * "no versions yet" message.
 */
function ProfileHistoryDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { data, isLoading } = useQuery<CandidateProfileHistoryEntry[]>({
    queryKey: ['candidate', 'profile-history'],
    queryFn: async () => {
      try {
        return (await api.get('/api/candidate-profile/history')).data;
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
            <History className="h-4 w-4 text-primary" /> Profile evolution
          </span>
        </DialogTitle>
        <DialogDescription>Every regeneration of your intelligence profile, newest first.</DialogDescription>
      </DialogHeader>
      <DialogBody className="space-y-4">
        {isLoading ? (
          <Skeleton className="h-32 w-full" />
        ) : entries.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No previous versions yet — history appears after the profile is rebuilt or preferences change.
          </p>
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
                    {` · ${e.after.skills?.length ?? 0} skills · ${e.after.targetRoles?.length ?? 0} target roles`}
                    {e.before && (e.after.skills?.length ?? 0) !== (e.before.skills?.length ?? 0) && (
                      <span className="ml-1.5 text-primary">
                        ({(e.after.skills?.length ?? 0) > (e.before.skills?.length ?? 0) ? '+' : ''}
                        {(e.after.skills?.length ?? 0) - (e.before.skills?.length ?? 0)} skills)
                      </span>
                    )}
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
