import { useMemo, useState } from 'react';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { motion, AnimatePresence } from 'framer-motion';
import {
  Award,
  Bot,
  Briefcase,
  Building2,
  ChevronDown,
  ChevronUp,
  Clock,
  Compass,
  FileText,
  GraduationCap,
  Mic,
  Sparkles,
  User,
} from 'lucide-react';
import { api } from '@/lib/api';
import { careerTimeline, type CareerTimelineCategory, type CareerTimelineEntry } from '@/lib/careerTimeline';
import { PageHeader } from '@/components/common/PageHeader';
import { Card, CardContent } from '@/components/ui/card';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';

/** Minimal shape needed here — mirrors the fuller `MissionResponse` interface in MissionDashboard.tsx. */
interface MissionSummary {
  status: string;
  targetRole: string;
}

const CATEGORY_META: Record<
  CareerTimelineCategory,
  { label: string; icon: typeof Sparkles; tone: BadgeTone }
> = {
  MISSION: { label: 'Mission', icon: Compass, tone: 'primary' },
  APPLICATION: { label: 'Application', icon: Briefcase, tone: 'info' },
  RESUME: { label: 'Resume', icon: FileText, tone: 'neutral' },
  LEARNING: { label: 'Learning', icon: GraduationCap, tone: 'success' },
  INTERVIEW: { label: 'Interview', icon: Mic, tone: 'warning' },
  MEMORY: { label: 'Memory', icon: Sparkles, tone: 'primary' },
  COMPANY: { label: 'Company', icon: Building2, tone: 'info' },
  WORKFLOW: { label: 'AI Workflow', icon: Bot, tone: 'primary' },
};

const CATEGORIES = Object.keys(CATEGORY_META) as CareerTimelineCategory[];

/** Static class strings only — Tailwind's JIT compiler can't see interpolated `bg-${x}/10`. */
const ICON_BG_CLASSES: Record<BadgeTone, string> = {
  primary: 'bg-primary/10',
  info: 'bg-secondary/10',
  success: 'bg-success/10',
  warning: 'bg-warning/10',
  danger: 'bg-danger/10',
  neutral: 'bg-muted',
};

function relativeTime(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso).getTime();
  if (Number.isNaN(d)) return '—';
  const diffMs = Date.now() - d;
  const mins = Math.round(diffMs / 60_000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.round(hrs / 24);
  if (days < 30) return `${days}d ago`;
  const months = Math.round(days / 30);
  return `${months}mo ago`;
}

/**
 * Phase 10H — Career Journey: the first single, cross-category, chronological feed spanning
 * Mission/Application/Resume/Learning/Interview/Memory/Company/AI-Workflow events. Every event
 * is sourced from an already-existing entity via the additive `GET /api/career-timeline`
 * aggregation endpoint — nothing here is fabricated. There is deliberately no "Analytics" filter:
 * no discrete analytics event exists anywhere in the backend (only raw numeric snapshots), and
 * inventing one here would violate this whole page's honesty premise.
 */
export default function CareerJourney() {
  const [category, setCategory] = useState<CareerTimelineCategory | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const missionQuery = useQuery<MissionSummary[]>({
    queryKey: ['missions'],
    queryFn: async () => {
      try {
        return (await api.get('/api/missions')).data;
      } catch {
        return [];
      }
    },
    retry: false,
  });
  const currentMission = (missionQuery.data ?? []).find((m) => m.status === 'ACTIVE') ?? missionQuery.data?.[0];

  const {
    data,
    isLoading,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
  } = useInfiniteQuery({
    queryKey: ['career-timeline', category],
    initialPageParam: 0,
    queryFn: async ({ pageParam }) => careerTimeline.get({ category, page: pageParam as number, size: 25 }),
    getNextPageParam: (lastPage, allPages) => (lastPage.hasMore ? allPages.length : undefined),
    retry: false,
  });

  const enabled = data?.pages[0]?.enabled ?? true;
  const entries = useMemo(() => data?.pages.flatMap((p) => p.entries) ?? [], [data]);

  function toggleExpanded(id: string) {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  const lastActivity = entries[0]?.occurredAt;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Career Journey"
        description="Your complete professional journey — every mission, application, resume change, interview, and AI action, in one honest timeline."
      />

      {/* Journey header stats — only real, currently-known data. No "career age"/"career score"
          shown: no account-creation timestamp or single career-score concept exists anywhere in
          the backend, and inventing either would violate this page's own honesty premise. */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <Card>
          <CardContent className="flex items-center gap-3 p-4">
            <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
              <Compass className="h-4 w-4" />
            </span>
            <div className="min-w-0">
              <p className="text-xs text-muted-foreground">Current mission</p>
              <p className="truncate text-sm font-semibold text-foreground">
                {missionQuery.isLoading ? '—' : currentMission?.targetRole || 'No active mission'}
              </p>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="flex items-center gap-3 p-4">
            <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-secondary/10 text-secondary">
              <Clock className="h-4 w-4" />
            </span>
            <div className="min-w-0">
              <p className="text-xs text-muted-foreground">Last activity</p>
              <p className="text-sm font-semibold text-foreground">{isLoading ? '—' : relativeTime(lastActivity)}</p>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="flex items-center gap-3 p-4">
            <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-success/10 text-success">
              <Award className="h-4 w-4" />
            </span>
            <div className="min-w-0">
              <p className="text-xs text-muted-foreground">Events shown</p>
              <p className="text-sm font-semibold text-foreground">
                {entries.length}
                {hasNextPage ? '+' : ''}
              </p>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Category filters */}
      <div className="flex flex-wrap gap-1.5" role="tablist" aria-label="Filter by category">
        <Button
          size="sm"
          variant={category === null ? 'secondary' : 'ghost'}
          role="tab"
          aria-selected={category === null}
          onClick={() => setCategory(null)}
        >
          All
        </Button>
        {CATEGORIES.map((c) => {
          const meta = CATEGORY_META[c];
          const Icon = meta.icon;
          return (
            <Button
              key={c}
              size="sm"
              variant={category === c ? 'secondary' : 'ghost'}
              role="tab"
              aria-selected={category === c}
              onClick={() => setCategory(c)}
            >
              <Icon className="h-3.5 w-3.5" /> {meta.label}
            </Button>
          );
        })}
      </div>

      {!enabled && !isLoading ? (
        <EmptyState
          icon={Sparkles}
          title="Career Journey isn't enabled yet"
          description="This feature may be disabled for your account. No verified event available."
        />
      ) : isLoading ? (
        <div className="space-y-3">
          {Array.from({ length: 5 }).map((_, i) => (
            <Skeleton key={i} className="h-24 w-full" />
          ))}
        </div>
      ) : entries.length === 0 ? (
        <EmptyState
          icon={Sparkles}
          title="No verified event available"
          description="Nothing has happened yet in this category. As you build missions, apply to jobs, and use the AI copilot, your journey appears here."
        />
      ) : (
        <motion.ol
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ duration: 0.25, ease: 'easeOut' }}
          className="space-y-3"
          aria-label="Career timeline events"
        >
          {entries.map((entry, i) => (
            <TimelineCard
              key={entry.id + entry.eventType + i}
              entry={entry}
              isOpen={expanded.has(entry.id)}
              onToggle={() => toggleExpanded(entry.id)}
            />
          ))}
        </motion.ol>
      )}

      {hasNextPage && (
        <div className="flex justify-center pt-2">
          <Button variant="outline" onClick={() => fetchNextPage()} loading={isFetchingNextPage}>
            Load more
          </Button>
        </div>
      )}
    </div>
  );
}

function TimelineCard({
  entry,
  isOpen,
  onToggle,
}: {
  entry: CareerTimelineEntry;
  isOpen: boolean;
  onToggle: () => void;
}) {
  const meta = CATEGORY_META[entry.category];
  const Icon = meta.icon;
  const hasDetail = !!(entry.eventType || entry.source || entry.relatedJobId || entry.relatedCompanyId || entry.relatedMissionId);

  return (
    <motion.li
      layout
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.2 }}
      className="rounded-xl border border-border bg-card p-4"
    >
      <div className="flex items-start gap-3">
        <span className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full ${ICON_BG_CLASSES[meta.tone]}`}>
          <Icon className="h-4 w-4 text-foreground" aria-hidden="true" />
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-1.5">
            <p className="text-sm font-semibold text-foreground">{entry.title}</p>
            <Badge tone={meta.tone}>{meta.label}</Badge>
            {entry.aiGenerated != null && (
              <Badge tone={entry.aiGenerated ? 'primary' : 'neutral'}>
                {entry.aiGenerated ? <Bot className="h-3 w-3" /> : <User className="h-3 w-3" />}
                {entry.aiGenerated ? 'AI' : 'Manual'}
              </Badge>
            )}
          </div>
          {entry.description && <p className="mt-1 text-xs text-muted-foreground">{entry.description}</p>}
          <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
            <span className="flex items-center gap-1">
              <Clock className="h-3 w-3" /> {relativeTime(entry.occurredAt)}
            </span>
            {entry.relatedCompanyName && <Badge tone="neutral">{entry.relatedCompanyName}</Badge>}
          </div>

          {hasDetail && (
            <>
              <button
                type="button"
                onClick={onToggle}
                aria-expanded={isOpen}
                className="mt-2 flex items-center gap-1 text-xs font-medium text-primary hover:underline"
              >
                {isOpen ? <ChevronUp className="h-3.5 w-3.5" /> : <ChevronDown className="h-3.5 w-3.5" />}
                {isOpen ? 'Hide details' : 'Details'}
              </button>
              <AnimatePresence initial={false}>
                {isOpen && (
                  <motion.div
                    initial={{ height: 0, opacity: 0 }}
                    animate={{ height: 'auto', opacity: 1 }}
                    exit={{ height: 0, opacity: 0 }}
                    transition={{ duration: 0.2 }}
                    className="overflow-hidden"
                  >
                    <div className="mt-2 grid grid-cols-2 gap-2 rounded-lg bg-muted/40 p-3 text-xs text-muted-foreground">
                      <div><span className="font-medium text-foreground">Event type: </span>{entry.eventType}</div>
                      {entry.source && <div><span className="font-medium text-foreground">Source: </span>{entry.source.replace(/_/g, ' ').toLowerCase()}</div>}
                      <div><span className="font-medium text-foreground">Occurred: </span>{new Date(entry.occurredAt).toLocaleString()}</div>
                      {entry.relatedMissionId && <div className="col-span-2 truncate"><span className="font-medium text-foreground">Mission: </span>{entry.relatedMissionId}</div>}
                      {entry.relatedJobId && <div className="col-span-2 truncate"><span className="font-medium text-foreground">Job: </span>{entry.relatedJobId}</div>}
                      {entry.relatedCompanyId && <div className="col-span-2 truncate"><span className="font-medium text-foreground">Company: </span>{entry.relatedCompanyName ?? entry.relatedCompanyId}</div>}
                    </div>
                  </motion.div>
                )}
              </AnimatePresence>
            </>
          )}
        </div>
      </div>
    </motion.li>
  );
}
