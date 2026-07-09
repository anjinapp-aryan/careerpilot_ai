import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, ShieldAlert, ShieldCheck, ShieldQuestion, XCircle } from 'lucide-react';
import { api } from '@/lib/api';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useToast } from '@/components/ui/toast';
import type { ApprovalQueueItem } from '@/types/workflow';

const VERDICT_TONE: Record<string, BadgeTone> = {
  SAFE: 'success',
  REVIEW: 'warning',
  BLOCK: 'danger',
};

function verdictIcon(verdict: string) {
  const v = verdict.toUpperCase();
  if (v === 'SAFE') return <ShieldCheck className="h-3.5 w-3.5" />;
  if (v === 'BLOCK') return <ShieldAlert className="h-3.5 w-3.5" />;
  return <ShieldQuestion className="h-3.5 w-3.5" />;
}

/**
 * Phase 2E — the human approval gate for auto-apply execution. Reads GET /api/execution/pending
 * and drives the ONLY trigger for execution (POST /api/execution/approve/{id}). The whole 2E
 * engine ships dark, so the pending queue is empty on a stock stack and this panel renders
 * nothing at all — it appears only when there is something to decide.
 */
export function ExecutionApprovalsPanel() {
  const qc = useQueryClient();
  const { toast } = useToast();
  const [note, setNote] = useState('');

  const pending = useQuery<ApprovalQueueItem[]>({
    queryKey: ['execution', 'pending-approvals'],
    queryFn: async () => {
      try {
        return (await api.get('/api/execution/pending')).data;
      } catch {
        return [];
      }
    },
    retry: false,
    staleTime: 30_000,
  });

  const decide = useMutation({
    mutationFn: async ({ id, action }: { id: string; action: 'approve' | 'reject' }) =>
      (await api.post(`/api/execution/${action}/${id}`, note.trim() ? { note: note.trim() } : {})).data,
    onSuccess: (_d, v) => {
      qc.invalidateQueries({ queryKey: ['execution', 'pending-approvals'] });
      setNote('');
      toast({
        variant: v.action === 'approve' ? 'success' : 'default',
        title: v.action === 'approve' ? 'Execution approved' : 'Execution rejected',
        description: v.action === 'approve' ? 'The application will be submitted by the execution engine.' : 'Nothing will be submitted.',
      });
    },
    onError: (e: any) =>
      toast({
        variant: 'error',
        title: 'Decision failed',
        description: e?.response?.status === 409 ? 'Already decided by another session.' : 'Please try again.',
      }),
  });

  const entries = (pending.data ?? []).filter((e) => e.status === 'PENDING');
  if (pending.isLoading || entries.length === 0) return null;

  return (
    <Card className="border-warning/40 bg-warning/5">
      <CardHeader className="flex-row items-center justify-between">
        <CardTitle className="flex items-center gap-2 text-base">
          <ShieldQuestion className="h-4 w-4 text-warning" /> Pending execution approvals
        </CardTitle>
        <Badge tone="warning">{entries.length} awaiting decision</Badge>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-xs text-muted-foreground">
          The auto-apply engine never submits without your explicit approval. Each entry below is an
          application package the safety engine has screened and parked for you.
        </p>
        {entries.map((e) => (
          <div key={e.id} className="rounded-lg border border-border bg-card p-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-xs text-muted-foreground" title={e.jobId}>
                job #{e.jobId.slice(0, 8)}
              </span>
              <Badge tone={VERDICT_TONE[e.safetyVerdict?.toUpperCase()] ?? 'neutral'}>
                {verdictIcon(e.safetyVerdict ?? '')} {e.safetyVerdict}
              </Badge>
              <span className="text-xs text-muted-foreground">
                requested {new Date(e.requestedAt).toLocaleString()}
              </span>
              {e.expiresAt && (
                <span className="text-xs text-muted-foreground">· expires {new Date(e.expiresAt).toLocaleString()}</span>
              )}
              <span className="ml-auto flex items-center gap-2">
                <Button
                  size="sm"
                  variant="success"
                  onClick={() => decide.mutate({ id: e.id, action: 'approve' })}
                  disabled={decide.isPending}
                >
                  <CheckCircle2 className="h-3.5 w-3.5" /> Approve
                </Button>
                <Button
                  size="sm"
                  variant="danger"
                  onClick={() => decide.mutate({ id: e.id, action: 'reject' })}
                  disabled={decide.isPending}
                >
                  <XCircle className="h-3.5 w-3.5" /> Reject
                </Button>
              </span>
            </div>
          </div>
        ))}
        <Input
          value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder="Optional decision note (recorded in the approval audit)…"
          className="h-9 text-xs"
        />
      </CardContent>
    </Card>
  );
}
