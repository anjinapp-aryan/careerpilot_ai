import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Sparkles, RefreshCw, BadgeCheck } from 'lucide-react';
import { api } from '@/lib/api';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { useToast } from '@/components/ui/toast';
import type { CandidateProfile } from '@/types/workflow';

/**
 * Read-only summary of the canonical Candidate Intelligence Profile (Phase 1).
 * Ships dark: when the feature flag is off or no profile exists yet, the API returns 404 and
 * this component renders nothing — so it never affects the Recommended tab when disabled.
 */
export function CandidateProfileCard() {
  const qc = useQueryClient();
  const { toast } = useToast();

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
      <ChipRow label="Preferred locations" items={[...profile.preferredCountries, ...profile.preferredCities]} />
      <ChipRow label="Work modes" items={profile.workModes} />
    </Card>
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
