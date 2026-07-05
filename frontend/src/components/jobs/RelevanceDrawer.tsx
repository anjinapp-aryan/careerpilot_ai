import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CheckCircle2, HelpCircle, Sparkles, XCircle } from 'lucide-react';
import { api } from '@/lib/api';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs } from '@/components/ui/tabs';
import { Dialog, DialogBody, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import type { JobRelevance } from '@/types/workflow';

type RelevanceTab = 'summary' | 'analysis' | 'reasons';

interface RelevanceDrawerProps {
  jobId: string | null;
  jobTitle?: string;
  onClose: () => void;
}

function strengthTone(strength: string): 'success' | 'primary' | 'warning' | 'danger' {
  const s = strength.toLowerCase();
  if (s.includes('excellent') || s.includes('strong')) return 'success';
  if (s.includes('good')) return 'primary';
  if (s.includes('weak')) return 'warning';
  return 'danger';
}

function Check({ ok, label }: { ok: boolean; label: string }) {
  return (
    <li className="flex items-center gap-2 text-sm">
      {ok ? (
        <CheckCircle2 className="h-4 w-4 shrink-0 text-success" />
      ) : (
        <XCircle className="h-4 w-4 shrink-0 text-muted-foreground" />
      )}
      <span className={ok ? 'text-foreground' : 'text-muted-foreground'}>{label}</span>
    </li>
  );
}

/**
 * Phase 4.4 — "Why am I seeing this?" drawer. Consumes GET /api/jobs/{id}/relevance
 * (Phase 3B.1 explainability). The endpoint 404s when career.explainability.enabled is
 * false, so a failed fetch renders a quiet "not available yet" state rather than an error.
 */
export function RelevanceDrawer({ jobId, jobTitle, onClose }: RelevanceDrawerProps) {
  const [tab, setTab] = useState<RelevanceTab>('summary');
  useEffect(() => setTab('summary'), [jobId]);
  const { data, isLoading, isError } = useQuery<JobRelevance | null>({
    queryKey: ['jobs', 'relevance', jobId],
    queryFn: async () => {
      try {
        return (await api.get(`/api/jobs/${jobId}/relevance`)).data as JobRelevance;
      } catch {
        return null;
      }
    },
    enabled: !!jobId,
    retry: false,
    staleTime: 60_000,
  });

  return (
    <Dialog open={!!jobId} onOpenChange={(o) => !o && onClose()} size="md">
      <DialogHeader onClose={onClose}>
        <DialogTitle>
          <span className="flex items-center gap-2">
            <HelpCircle className="h-4 w-4 text-primary" /> Why am I seeing this?
          </span>
        </DialogTitle>
        <DialogDescription>{jobTitle ?? 'Career relevance breakdown for this role.'}</DialogDescription>
      </DialogHeader>
      <DialogBody className="space-y-4">
        {isLoading ? (
          <div className="space-y-2">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} className="h-6 w-full rounded-md" />
            ))}
          </div>
        ) : isError || !data ? (
          <p className="text-sm text-muted-foreground">
            Career relevance explainability isn't available for this role yet.
          </p>
        ) : (
          <>
            <Tabs
              items={[
                { value: 'summary', label: 'Summary' },
                { value: 'analysis', label: 'Match Analysis' },
                { value: 'reasons', label: 'Reasons', count: data.reasons.length },
              ]}
              value={tab}
              onChange={(v) => setTab(v as RelevanceTab)}
            />

            {tab === 'summary' && (
              <div className="flex items-center justify-between rounded-lg border border-border bg-muted/30 px-3 py-2.5">
                <span className="flex items-center gap-1.5 text-sm font-medium text-foreground">
                  <Sparkles className="h-4 w-4 text-primary" /> Relevance score
                </span>
                <div className="flex items-center gap-2">
                  <span className="tabular-nums text-sm font-semibold text-foreground">{data.relevanceScore}%</span>
                  <Badge tone={strengthTone(data.matchStrength)}>{data.matchStrength}</Badge>
                </div>
              </div>
            )}

            {tab === 'analysis' && (
              <ul className="space-y-2 rounded-lg border border-border p-3">
                <Check ok={data.roleMatch} label="Role match" />
                <Check ok={data.skillOverlap > 0} label={`Skill overlap (${data.skillOverlap} matched)`} />
                <Check ok={data.experienceFit} label="Experience fit" />
                <Check ok={data.domainFit} label="Domain match" />
              </ul>
            )}

            {tab === 'reasons' && (
              data.reasons.length > 0 ? (
                <ul className="space-y-1.5">
                  {data.reasons.map((r, i) => (
                    <li key={i} className="flex gap-2 text-sm text-muted-foreground">
                      <span className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-muted-foreground" />
                      {r}
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="text-sm text-muted-foreground">No specific reasons recorded.</p>
              )
            )}
          </>
        )}
      </DialogBody>
    </Dialog>
  );
}
