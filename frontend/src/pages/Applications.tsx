import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import { motion } from 'framer-motion';
import { Building2, Clock, GripVertical, Info, KanbanSquare, ListTree, Sparkles, Target } from 'lucide-react';
import { api } from '@/lib/api';
import { PageHeader } from '@/components/common/PageHeader';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { useToast } from '@/components/ui/toast';
import { Tabs } from '@/components/ui/tabs';
import { Dialog, DialogBody, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { WorkflowTraceExplorer } from '@/components/workflow/WorkflowTraceExplorer';
import { ExecutionApprovalsPanel } from '@/components/execution/ExecutionApprovalsPanel';
import { cn } from '@/lib/cn';
import type { Application, Job, JobsPage } from '@/types/workflow';

/** GET /api/workflow/applications/{jobId}/lifecycle — Phase 3A lifecycle row + status history. */
interface LifecycleView {
  lifecycle?: {
    currentStatus?: string;
    previousStatus?: string | null;
    company?: string | null;
    country?: string | null;
    applicationDate?: string | null;
    source?: string | null;
    updatedAt?: string | null;
  } | null;
  history?: { fromStatus?: string | null; toStatus?: string | null; createdAt?: string | null }[];
}

/** GET /api/workflow/applications/{jobId}/timeline — Phase 3A observable-event timeline. */
interface TimelineEntry {
  id: string;
  eventType: string;
  eventSource?: string | null;
  confidence?: number | null;
  details?: string | null;
  occurredAt?: string | null;
}

interface DetailTarget {
  jobId: string;
  title: string;
  company: string;
}

interface Column {
  id: string;
  label: string;
  tone: 'neutral' | 'info' | 'primary' | 'success' | 'danger';
  dot: string;
}

const COLUMNS: Column[] = [
  { id: 'SAVED', label: 'Saved', tone: 'neutral', dot: 'bg-muted-foreground' },
  { id: 'APPLIED', label: 'Applied', tone: 'info', dot: 'bg-secondary' },
  { id: 'INTERVIEWING', label: 'Interview', tone: 'primary', dot: 'bg-primary' },
  { id: 'OFFER', label: 'Offer', tone: 'success', dot: 'bg-success' },
  { id: 'REJECTED', label: 'Rejected', tone: 'danger', dot: 'bg-danger' },
  // Phase 4.7 — WITHDRAWN is a real, writable status on the core `applications.status` column
  // (see Application.java); unlike "Viewed"/"Archived" (which have no core-status backing and
  // are deferred), this column is a full drag-and-drop target.
  { id: 'WITHDRAWN', label: 'Withdrawn', tone: 'neutral', dot: 'bg-muted-foreground' },
];

export default function Applications() {
  const qc = useQueryClient();
  const { toast } = useToast();
  const [activeId, setActiveId] = useState<string | null>(null);
  const [detail, setDetail] = useState<DetailTarget | null>(null);

  const { data: apps = [], isLoading } = useQuery<Application[]>({
    queryKey: ['applications'],
    queryFn: async () => (await api.get('/api/applications')).data,
  });
  const { data: jobsPage } = useQuery<JobsPage>({
    queryKey: ['jobs', ''],
    queryFn: async () => (await api.get('/api/jobs')).data,
  });

  const jobMap = useMemo(() => {
    const m = new Map<string, Job>();
    (jobsPage?.content ?? []).forEach((j) => m.set(j.id, j));
    return m;
  }, [jobsPage]);

  const move = useMutation({
    mutationFn: async ({ id, status }: { id: string; status: string }) =>
      (await api.patch(`/api/applications/${id}`, { status })).data,
    onMutate: async ({ id, status }) => {
      await qc.cancelQueries({ queryKey: ['applications'] });
      const prev = qc.getQueryData<Application[]>(['applications']);
      qc.setQueryData<Application[]>(['applications'], (old) =>
        (old ?? []).map((a) => (a.id === id ? { ...a, status } : a)),
      );
      return { prev };
    },
    onError: (_e, _v, ctx) => {
      if (ctx?.prev) qc.setQueryData(['applications'], ctx.prev);
      toast({ variant: 'error', title: 'Could not move card' });
    },
    onSettled: () => {
      qc.invalidateQueries({ queryKey: ['applications'] });
      qc.invalidateQueries({ queryKey: ['dashboard'] });
    },
  });

  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));

  const byStatus = useMemo(() => {
    const groups: Record<string, Application[]> = {};
    COLUMNS.forEach((c) => (groups[c.id] = []));
    apps.forEach((a) => {
      (groups[a.status] ??= []).push(a);
    });
    return groups;
  }, [apps]);

  const activeApp = apps.find((a) => a.id === activeId) ?? null;

  function onDragStart(e: DragStartEvent) {
    setActiveId(String(e.active.id));
  }
  function onDragEnd(e: DragEndEvent) {
    setActiveId(null);
    const { active, over } = e;
    if (!over) return;
    const id = String(active.id);
    const target = String(over.id);
    const app = apps.find((a) => a.id === id);
    if (app && app.status !== target && COLUMNS.some((c) => c.id === target)) {
      move.mutate({ id, status: target });
    }
  }

  if (isLoading) {
    return (
      <div className="space-y-6">
        <PageHeader title="Applications" description="Track every opportunity through your pipeline." />
        <div className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-6">
          {COLUMNS.map((c) => (
            <Skeleton key={c.id} className="h-96 rounded-xl" />
          ))}
        </div>
      </div>
    );
  }

  if (apps.length === 0) {
    return (
      <div className="space-y-6">
        <PageHeader title="Applications" description="Track every opportunity through your pipeline." />
        <EmptyState
          icon={KanbanSquare}
          title="No applications yet"
          description="Save or apply to jobs from the Jobs page — they'll show up here on your pipeline board."
        />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Applications"
        description="Drag cards between stages to update their status in real time."
        actions={
          <Badge tone="primary">
            <Target className="h-3 w-3" /> {apps.length} total
          </Badge>
        }
      />

      {/* Phase 2E — pending auto-apply approvals; renders nothing while the engine is dark. */}
      <ExecutionApprovalsPanel />

      <DndContext sensors={sensors} onDragStart={onDragStart} onDragEnd={onDragEnd}>
        <div className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-6">
          {COLUMNS.map((col) => (
            <KanbanColumn
              key={col.id}
              column={col}
              apps={byStatus[col.id] ?? []}
              jobMap={jobMap}
              onDetails={(app) => {
                const job = jobMap.get(app.jobId);
                setDetail({ jobId: app.jobId, title: job?.title ?? 'Application', company: job?.company ?? '' });
              }}
            />
          ))}
        </div>

        <DragOverlay>
          {activeApp ? <AppCard app={activeApp} job={jobMap.get(activeApp.jobId)} overlay /> : null}
        </DragOverlay>
      </DndContext>

      <ApplicationDetailDialog target={detail} onClose={() => setDetail(null)} />
    </div>
  );
}

function KanbanColumn({
  column,
  apps,
  jobMap,
  onDetails,
}: {
  column: Column;
  apps: Application[];
  jobMap: Map<string, Job>;
  onDetails: (app: Application) => void;
}) {
  const { setNodeRef, isOver } = useDroppable({ id: column.id });
  return (
    <div className="flex min-h-[24rem] flex-col">
      <div className="mb-2 flex items-center justify-between px-1">
        <div className="flex items-center gap-2">
          <span className={cn('h-2 w-2 rounded-full', column.dot)} />
          <span className="text-sm font-semibold text-foreground">{column.label}</span>
        </div>
        <span className="rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
          {apps.length}
        </span>
      </div>
      <div
        ref={setNodeRef}
        className={cn(
          'flex flex-1 flex-col gap-2 rounded-xl border border-dashed p-2 transition-colors',
          isOver ? 'border-primary bg-primary/5' : 'border-border bg-muted/20',
        )}
      >
        {apps.map((app) => (
          <AppCard key={app.id} app={app} job={jobMap.get(app.jobId)} onDetails={() => onDetails(app)} />
        ))}
        {apps.length === 0 && (
          <div className="flex flex-1 items-center justify-center py-8 text-center text-xs text-muted-foreground">
            Drop here
          </div>
        )}
      </div>
    </div>
  );
}

function AppCard({
  app,
  job,
  overlay,
  onDetails,
}: {
  app: Application;
  job?: Job;
  overlay?: boolean;
  onDetails?: () => void;
}) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: app.id });

  return (
    <motion.div
      ref={overlay ? undefined : setNodeRef}
      {...(overlay ? {} : attributes)}
      {...(overlay ? {} : listeners)}
      layout
      className={cn(
        'group rounded-lg border border-border bg-card p-3 shadow-xs',
        overlay ? 'rotate-2 shadow-lg' : 'cursor-grab active:cursor-grabbing hover:shadow-md',
        isDragging && !overlay && 'opacity-40',
      )}
    >
      <div className="flex items-start gap-2">
        <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-primary/10 text-xs font-semibold text-primary">
          {(job?.company ?? '?').slice(0, 2).toUpperCase()}
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium text-foreground">
            {job?.title ?? 'Job'}
          </p>
          <p className="flex items-center gap-1 truncate text-xs text-muted-foreground">
            <Building2 className="h-3 w-3" /> {job?.company ?? `#${app.jobId.slice(0, 8)}`}
          </p>
        </div>
        <GripVertical className="h-4 w-4 shrink-0 text-muted-foreground/40 group-hover:text-muted-foreground" />
      </div>

      <div className="mt-2.5 flex items-center justify-between gap-1.5">
        <div className="flex items-center gap-1.5">
          {app.matchScore != null && (
            <Badge tone="primary" className="text-[10px]">Match {app.matchScore}</Badge>
          )}
          {app.atsScore != null && (
            <Badge tone="info" className="text-[10px]">ATS {app.atsScore}</Badge>
          )}
        </div>
        {!overlay && onDetails && (
          <button
            type="button"
            // Pointer events must not reach the draggable listeners, or a click starts a drag.
            onPointerDown={(e) => e.stopPropagation()}
            onClick={(e) => {
              e.stopPropagation();
              onDetails();
            }}
            aria-label="View workflow details"
            className="rounded-md p-1 text-muted-foreground/60 transition-colors hover:bg-muted hover:text-foreground"
          >
            <Info className="h-3.5 w-3.5" />
          </button>
        )}
      </div>
    </motion.div>
  );
}

/**
 * Phase 3B.4 — application workflow drawer. Reads the Phase 3A read-model
 * (lifecycle row + status history + observable-event timeline) for one job.
 * All three engines ship dark, so the endpoints 404 / return empty on a stock
 * stack — the drawer degrades to a "workflow tracking not enabled" message
 * rather than erroring.
 */
function ApplicationDetailDialog({ target, onClose }: { target: DetailTarget | null; onClose: () => void }) {
  const jobId = target?.jobId ?? null;
  const { toast } = useToast();
  const [tab, setTab] = useState<'timeline' | 'trace'>('timeline');
  const [seededCorrelationId, setSeededCorrelationId] = useState<string | null>(null);

  useEffect(() => {
    setTab('timeline');
    setSeededCorrelationId(null);
  }, [jobId]);

  const seed = useMutation({
    mutationFn: async () => (await api.post(`/api/workflow/applications/${jobId}/seed`, {})).data as {
      status: string;
      correlationId?: string;
    },
    onSuccess: (data) => {
      if (data.status === 'SEEDED' && data.correlationId) {
        setSeededCorrelationId(data.correlationId);
        setTab('trace');
        toast({ variant: 'success', title: 'Workflow tracking seeded', description: 'Correlation id ready in the Trace tab.' });
      } else {
        toast({ variant: 'default', title: 'Workflow tracking is not enabled', description: 'Ask an admin to flip workflow.tracking.trigger.enabled.' });
      }
    },
    onError: () => toast({ variant: 'error', title: 'Could not seed workflow tracking' }),
  });

  const lifecycle = useQuery<LifecycleView | null>({
    queryKey: ['workflow', 'lifecycle', jobId],
    queryFn: async () => {
      try {
        return (await api.get(`/api/workflow/applications/${jobId}/lifecycle`)).data as LifecycleView;
      } catch {
        return null; // 404 when no lifecycle row exists (dark flags or never tracked)
      }
    },
    enabled: !!jobId,
    retry: false,
  });

  const timeline = useQuery<TimelineEntry[]>({
    queryKey: ['workflow', 'timeline', jobId],
    queryFn: async () => (await api.get(`/api/workflow/applications/${jobId}/timeline`)).data,
    enabled: !!jobId,
    retry: false,
  });

  const life = lifecycle.data?.lifecycle ?? null;
  const history = lifecycle.data?.history ?? [];
  const events = timeline.data ?? [];
  const loading = lifecycle.isLoading || timeline.isLoading;
  const nothing = !loading && !life && events.length === 0;

  return (
    <Dialog open={!!target} onOpenChange={(o) => !o && onClose()} size="lg">
      <DialogHeader onClose={onClose}>
        <DialogTitle>
          <span className="flex items-center gap-2">
            <ListTree className="h-4 w-4 text-primary" /> Application workflow
          </span>
        </DialogTitle>
        <DialogDescription>
          {target?.title}
          {target?.company ? ` · ${target.company}` : ''}
        </DialogDescription>
      </DialogHeader>
      <DialogBody className="space-y-4">
        <Tabs
          items={[
            { value: 'timeline', label: 'Summary & Timeline' },
            { value: 'trace', label: 'Trace' },
          ]}
          value={tab}
          onChange={(v) => setTab(v as 'timeline' | 'trace')}
        />

        {tab === 'trace' ? (
          <div className="space-y-4">
            <div className="flex items-center justify-between gap-3 rounded-lg border border-border bg-muted/20 p-3">
              <p className="text-xs text-muted-foreground">
                Manually seed Phase 3A workflow tracking for this application (gated by
                <code className="mx-1 rounded bg-muted px-1 py-0.5">workflow.tracking.trigger.enabled</code>
                — a no-op with stock defaults).
              </p>
              <Button size="sm" variant="outline" onClick={() => seed.mutate()} loading={seed.isPending}>
                <Sparkles className="h-3.5 w-3.5" /> Seed tracking
              </Button>
            </div>
            <WorkflowTraceExplorer key={seededCorrelationId ?? 'empty'} initialCorrelationId={seededCorrelationId} compact />
          </div>
        ) : loading ? (
          <div className="space-y-3">
            {Array.from({ length: 3 }).map((_, i) => (
              <Skeleton key={i} className="h-14 rounded-lg" />
            ))}
          </div>
        ) : nothing ? (
          <div className="rounded-lg border border-border bg-muted/20 p-4 text-sm text-muted-foreground">
            <Info className="mb-1.5 h-4 w-4" />
            No workflow tracking for this application yet. The Phase 3A lifecycle engine is dark by
            default — once enabled, its status transitions and event timeline appear here.
          </div>
        ) : (
          <>
            {life && (
              <div>
                <h4 className="mb-2 text-sm font-semibold text-foreground">Lifecycle</h4>
                <div className="flex flex-wrap items-center gap-2">
                  {life.previousStatus && <Badge tone="neutral">{life.previousStatus}</Badge>}
                  {life.previousStatus && <span className="text-muted-foreground">→</span>}
                  <Badge tone="primary">{life.currentStatus ?? 'UNKNOWN'}</Badge>
                </div>
                <dl className="mt-3 grid grid-cols-2 gap-x-6 gap-y-1 text-xs text-muted-foreground">
                  {life.country && (
                    <div className="flex gap-1.5"><dt className="font-medium text-foreground">Country:</dt><dd>{life.country}</dd></div>
                  )}
                  {life.source && (
                    <div className="flex gap-1.5"><dt className="font-medium text-foreground">Source:</dt><dd>{life.source}</dd></div>
                  )}
                  {life.updatedAt && (
                    <div className="flex gap-1.5"><dt className="font-medium text-foreground">Updated:</dt><dd>{new Date(life.updatedAt).toLocaleString()}</dd></div>
                  )}
                </dl>
              </div>
            )}

            {history.length > 0 && (
              <div>
                <h4 className="mb-2 text-sm font-semibold text-foreground">Status history</h4>
                <div className="space-y-1.5">
                  {history.map((h, i) => (
                    <div key={i} className="flex items-center gap-2 text-xs text-muted-foreground">
                      <span className="tabular-nums">{h.fromStatus ?? '—'} → <span className="font-medium text-foreground">{h.toStatus ?? '—'}</span></span>
                      {h.createdAt && <span className="ml-auto">{new Date(h.createdAt).toLocaleString()}</span>}
                    </div>
                  ))}
                </div>
              </div>
            )}

            {events.length > 0 && (
              <div>
                <h4 className="mb-2 text-sm font-semibold text-foreground">Event timeline</h4>
                <ol className="space-y-2.5 border-l border-border pl-4">
                  {events.map((e) => (
                    <li key={e.id} className="relative">
                      <span className="absolute -left-[21px] top-1 h-2 w-2 rounded-full bg-primary" />
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-medium text-foreground">{e.eventType}</span>
                        {e.eventSource && <Badge tone="neutral" className="text-[10px]">{e.eventSource}</Badge>}
                        {typeof e.confidence === 'number' && (
                          <span className="text-[11px] text-muted-foreground">{Math.round(e.confidence * 100)}% conf</span>
                        )}
                      </div>
                      {e.details && <p className="text-xs text-muted-foreground">{e.details}</p>}
                      {e.occurredAt && (
                        <p className="flex items-center gap-1 text-[11px] text-muted-foreground">
                          <Clock className="h-3 w-3" /> {new Date(e.occurredAt).toLocaleString()}
                        </p>
                      )}
                    </li>
                  ))}
                </ol>
              </div>
            )}
          </>
        )}
      </DialogBody>
    </Dialog>
  );
}
