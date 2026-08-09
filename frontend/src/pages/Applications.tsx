import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import {
  Briefcase,
  Clock,
  Gauge,
  KanbanSquare,
  Percent,
  ShieldAlert,
  Target,
  TrendingUp,
  Trophy,
  Users,
} from 'lucide-react';
import { api } from '@/lib/api';
import { PageHeader } from '@/components/common/PageHeader';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { useToast } from '@/components/ui/toast';
import { KpiCard } from '@/components/dashboard/KpiCard';
import { ExecutionApprovalsPanel } from '@/components/execution/ExecutionApprovalsPanel';
import { MySubmissionsPanel } from '@/components/submission/MySubmissionsPanel';
import { ApplicationCardV2 } from '@/components/applications/ApplicationCardV2';
import { ApplicationDrawer } from '@/components/applications/ApplicationDrawer';
import { SmartFilterBar, DEFAULT_FILTERS, type SmartFilters } from '@/components/applications/SmartFilterBar';
import { BulkActionsToolbar } from '@/components/applications/BulkActionsToolbar';
import { cn } from '@/lib/cn';
import type { ApplicationCard, ApplicationBulkAction, ApplicationBulkResult } from '@/types/workflow';

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

const ACTIVE_STATUSES = new Set(['APPLIED', 'INTERVIEWING']);
const STALE_HEALTH = new Set(['STALE', 'COLD', 'RISK']);

export default function Applications() {
  const qc = useQueryClient();
  const { toast } = useToast();
  const [activeId, setActiveId] = useState<string | null>(null);
  const [detailId, setDetailId] = useState<string | null>(null);
  const [filters, setFilters] = useState<SmartFilters>(DEFAULT_FILTERS);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulkPending, setBulkPending] = useState<ApplicationBulkAction | null>(null);

  // Applications Command Center — richer per-card data (job/artifact joins + deterministic
  // health/recommendation/next-action). Additive endpoint; GET /api/applications (plain shape) is
  // untouched and still used elsewhere (e.g. Jobs.tsx).
  const { data: cards = [], isLoading } = useQuery<ApplicationCard[]>({
    queryKey: ['applications', 'cards'],
    queryFn: async () => (await api.get('/api/applications/cards')).data,
  });

  const move = useMutation({
    mutationFn: async ({ id, status }: { id: string; status: string }) =>
      (await api.patch(`/api/applications/${id}`, { status })).data,
    onMutate: async ({ id, status }) => {
      await qc.cancelQueries({ queryKey: ['applications'] });
      const prev = qc.getQueryData<ApplicationCard[]>(['applications', 'cards']);
      qc.setQueryData<ApplicationCard[]>(['applications', 'cards'], (old) =>
        (old ?? []).map((a) => (a.id === id ? { ...a, status } : a)),
      );
      return { prev };
    },
    onError: (_e, _v, ctx) => {
      if (ctx?.prev) qc.setQueryData(['applications', 'cards'], ctx.prev);
      toast({ variant: 'error', title: 'Could not move card' });
    },
    onSettled: () => {
      qc.invalidateQueries({ queryKey: ['applications'] });
      qc.invalidateQueries({ queryKey: ['dashboard'] });
    },
  });

  const toggleFavorite = useMutation({
    mutationFn: async ({ id, favorite }: { id: string; favorite: boolean }) =>
      (await api.patch(`/api/applications/${id}`, { favorite: String(favorite) })).data,
    onSettled: () => qc.invalidateQueries({ queryKey: ['applications'] }),
  });

  const bulk = useMutation({
    mutationFn: async ({ action, payload }: { action: ApplicationBulkAction; payload?: Record<string, string> }) => {
      setBulkPending(action);
      return (await api.post('/api/applications/bulk', { ids: Array.from(selected), action, payload })).data as ApplicationBulkResult;
    },
    onSuccess: (result) => {
      toast({ variant: 'success', title: `${result.applied} of ${result.requested} applications updated` });
      setSelected(new Set());
      qc.invalidateQueries({ queryKey: ['applications'] });
    },
    onError: () => toast({ variant: 'error', title: 'Bulk action failed' }),
    onSettled: () => setBulkPending(null),
  });

  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));

  const filtered = useMemo(() => {
    return cards.filter((c) => {
      if (c.archived) return false;
      if (filters.favoritesOnly && !c.favorite) return false;
      if (filters.remoteType && c.remoteType !== filters.remoteType) return false;
      if (filters.priority && c.priority !== filters.priority) return false;
      if (filters.health && c.healthStatus !== filters.health) return false;
      if (filters.query) {
        const q = filters.query.toLowerCase();
        const hay = `${c.jobTitle ?? ''} ${c.company ?? ''}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
  }, [cards, filters]);

  const byStatus = useMemo(() => {
    const groups: Record<string, ApplicationCard[]> = {};
    COLUMNS.forEach((c) => (groups[c.id] = []));
    filtered.forEach((a) => {
      (groups[a.status] ??= []).push(a);
    });
    return groups;
  }, [filtered]);

  const kpis = useMemo(() => computeKpis(cards), [cards]);

  const activeCard = cards.find((a) => a.id === activeId) ?? null;

  function onDragStart(e: DragStartEvent) {
    setActiveId(String(e.active.id));
  }
  function onDragEnd(e: DragEndEvent) {
    setActiveId(null);
    const { active, over } = e;
    if (!over) return;
    const id = String(active.id);
    const target = String(over.id);
    const app = cards.find((a) => a.id === id);
    if (app && app.status !== target && COLUMNS.some((c) => c.id === target)) {
      move.mutate({ id, status: target });
    }
  }

  function toggleSelect(id: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  if (isLoading) {
    return (
      <div className="space-y-6">
        <PageHeader title="Applications" description="Track every opportunity through your pipeline." />
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 xl:grid-cols-10">
          {Array.from({ length: 10 }).map((_, i) => (
            <Skeleton key={i} className="h-24 rounded-xl" />
          ))}
        </div>
        <div className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-6">
          {COLUMNS.map((c) => (
            <Skeleton key={c.id} className="h-96 rounded-xl" />
          ))}
        </div>
      </div>
    );
  }

  if (cards.length === 0) {
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
        description="Your AI-powered job application command center."
        actions={
          <Badge tone="primary">
            <Target className="h-3 w-3" /> {cards.length} total
          </Badge>
        }
      />

      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 xl:grid-cols-10">
        <KpiCard label="Applications" value={kpis.applications} icon={Briefcase} tone="primary" />
        <KpiCard label="Guided Apply" value={kpis.guidedApply} icon={ShieldAlert} tone="warning" hint="Needs manual completion" />
        <KpiCard label="Interviews" value={kpis.interviews} icon={Users} tone="info" />
        <KpiCard label="Offers" value={kpis.offers} icon={Trophy} tone="success" />
        <KpiCard label="Response rate" value={kpis.responseRate} suffix="%" icon={Percent} tone="info" />
        <KpiCard label="Avg ATS score" value={kpis.avgAts} icon={Gauge} tone="warning" hint="Across scored applications" />
        <KpiCard label="Avg match score" value={kpis.avgMatch} icon={Target} tone="primary" hint="Across scored applications" />
        <KpiCard label="Success rate" value={kpis.successRate} suffix="%" icon={TrendingUp} tone="success" hint="Offers / total" />
        <KpiCard label="Avg response time" value={kpis.avgResponseDays} suffix="d" icon={Clock} tone="warning" hint="Created → last update" />
        <KpiCard label="Pipeline velocity" value={kpis.pipelineVelocity} suffix="%" icon={Gauge} tone="info" hint="Active apps that aren't stale" />
      </div>

      {/* Phase 2E — pending auto-apply approvals; renders nothing while the engine is dark. */}
      <ExecutionApprovalsPanel />

      {/* Phase 7.16 — the user's submission sessions + generated artifacts; dark-safe (renders nothing when empty). */}
      <MySubmissionsPanel />

      <SmartFilterBar value={filters} onChange={setFilters} />

      <BulkActionsToolbar
        count={selected.size}
        pending={bulkPending}
        onArchive={() => bulk.mutate({ action: 'ARCHIVE' })}
        onExport={() => bulk.mutate({ action: 'EXPORT' })}
        onClear={() => setSelected(new Set())}
      />

      <DndContext sensors={sensors} onDragStart={onDragStart} onDragEnd={onDragEnd}>
        <div className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-6">
          {COLUMNS.map((col) => (
            <KanbanColumn
              key={col.id}
              column={col}
              cards={byStatus[col.id] ?? []}
              selected={selected}
              onToggleSelect={toggleSelect}
              onToggleFavorite={(c) => toggleFavorite.mutate({ id: c.id, favorite: !c.favorite })}
              onDetails={(c) => setDetailId(c.id)}
            />
          ))}
        </div>

        <DragOverlay>{activeCard ? <ApplicationCardV2 card={activeCard} overlay /> : null}</DragOverlay>
      </DndContext>

      <ApplicationDrawer applicationId={detailId} onClose={() => setDetailId(null)} />
    </div>
  );
}

function computeKpis(cards: ApplicationCard[]) {
  const total = cards.length;
  const interviews = cards.filter((c) => c.status === 'INTERVIEWING').length;
  const offers = cards.filter((c) => c.status === 'OFFER').length;
  const responded = cards.filter((c) => c.status !== 'SAVED').length;
  const respondedFurther = cards.filter((c) => ['INTERVIEWING', 'OFFER', 'REJECTED'].includes(c.status)).length;
  const responseRate = responded === 0 ? 0 : Math.round((respondedFurther / responded) * 100);
  const successRate = total === 0 ? 0 : Math.round((offers / total) * 100);

  const atsScores = cards.map((c) => c.atsScore).filter((v): v is number => v != null);
  const avgAts = atsScores.length === 0 ? 0 : Math.round(atsScores.reduce((a, b) => a + b, 0) / atsScores.length);

  const matchScores = cards.map((c) => c.matchScore).filter((v): v is number => v != null);
  const avgMatch = matchScores.length === 0 ? 0 : Math.round(matchScores.reduce((a, b) => a + b, 0) / matchScores.length);

  const responseDays = cards
    .filter((c) => c.status !== 'SAVED' && c.updatedAt)
    .map((c) => (new Date(c.updatedAt!).getTime() - new Date(c.createdAt).getTime()) / 86_400_000)
    .filter((d) => Number.isFinite(d) && d >= 0);
  const avgResponseDays = responseDays.length === 0 ? 0 : Math.round(responseDays.reduce((a, b) => a + b, 0) / responseDays.length);

  const active = cards.filter((c) => ACTIVE_STATUSES.has(c.status));
  const pipelineVelocity =
    active.length === 0 ? 0 : Math.round((active.filter((c) => !STALE_HEALTH.has(c.healthStatus)).length / active.length) * 100);

  const guidedApply = cards.filter((c) => c.guidedApplyRequired).length;

  return { applications: total, interviews, offers, responseRate, avgAts, avgMatch, successRate, avgResponseDays, pipelineVelocity, guidedApply };
}

function KanbanColumn({
  column,
  cards,
  selected,
  onToggleSelect,
  onToggleFavorite,
  onDetails,
}: {
  column: Column;
  cards: ApplicationCard[];
  selected: Set<string>;
  onToggleSelect: (id: string) => void;
  onToggleFavorite: (card: ApplicationCard) => void;
  onDetails: (card: ApplicationCard) => void;
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
          {cards.length}
        </span>
      </div>
      <div
        ref={setNodeRef}
        className={cn(
          'flex flex-1 flex-col gap-2 rounded-xl border border-dashed p-2 transition-colors',
          isOver ? 'border-primary bg-primary/5' : 'border-border bg-muted/20',
        )}
      >
        {cards.map((card) => (
          <ApplicationCardV2
            key={card.id}
            card={card}
            selected={selected.has(card.id)}
            onToggleSelect={() => onToggleSelect(card.id)}
            onToggleFavorite={() => onToggleFavorite(card)}
            onDetails={() => onDetails(card)}
          />
        ))}
        {cards.length === 0 && (
          <div className="flex flex-1 items-center justify-center py-8 text-center text-xs text-muted-foreground">
            Drop here
          </div>
        )}
      </div>
    </div>
  );
}
