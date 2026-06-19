import { useMemo, useState } from 'react';
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
import { Building2, GripVertical, KanbanSquare, Target } from 'lucide-react';
import { api } from '@/lib/api';
import { PageHeader } from '@/components/common/PageHeader';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { useToast } from '@/components/ui/toast';
import { cn } from '@/lib/cn';
import type { Application, Job, JobsPage } from '@/types/workflow';

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
];

export default function Applications() {
  const qc = useQueryClient();
  const { toast } = useToast();
  const [activeId, setActiveId] = useState<string | null>(null);

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
        <div className="grid grid-cols-2 gap-4 md:grid-cols-5">
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

      <DndContext sensors={sensors} onDragStart={onDragStart} onDragEnd={onDragEnd}>
        <div className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-5">
          {COLUMNS.map((col) => (
            <KanbanColumn
              key={col.id}
              column={col}
              apps={byStatus[col.id] ?? []}
              jobMap={jobMap}
            />
          ))}
        </div>

        <DragOverlay>
          {activeApp ? <AppCard app={activeApp} job={jobMap.get(activeApp.jobId)} overlay /> : null}
        </DragOverlay>
      </DndContext>
    </div>
  );
}

function KanbanColumn({
  column,
  apps,
  jobMap,
}: {
  column: Column;
  apps: Application[];
  jobMap: Map<string, Job>;
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
          <AppCard key={app.id} app={app} job={jobMap.get(app.jobId)} />
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
}: {
  app: Application;
  job?: Job;
  overlay?: boolean;
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

      {(app.matchScore != null || app.atsScore != null) && (
        <div className="mt-2.5 flex items-center gap-1.5">
          {app.matchScore != null && (
            <Badge tone="primary" className="text-[10px]">Match {app.matchScore}</Badge>
          )}
          {app.atsScore != null && (
            <Badge tone="info" className="text-[10px]">ATS {app.atsScore}</Badge>
          )}
        </div>
      )}
    </motion.div>
  );
}
