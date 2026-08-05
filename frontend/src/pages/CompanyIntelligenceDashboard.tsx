import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Building2, Clock, Cpu, GitCompare, Search, Users } from 'lucide-react';
import { api } from '@/lib/api';
import { PageHeader } from '@/components/common/PageHeader';
import { Tabs } from '@/components/ui/tabs';
import { Card } from '@/components/ui/card';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { EmptyState } from '@/components/ui/empty-state';
import { Skeleton } from '@/components/ui/skeleton';
import { Input } from '@/components/ui/input';
import { CompanyIntelPanel } from '@/components/company/CompanyIntelPanel';
import { companyIntel, type CompanySummary } from '@/lib/companyIntel';

const TABS = [
  { value: 'overview', label: 'Overview' },
  { value: 'hiring', label: 'Hiring Intelligence' },
  { value: 'interview', label: 'Interview Intelligence' },
  { value: 'technology', label: 'Technology' },
  { value: 'compare', label: 'Compare' },
];

function scoreTone(v?: number | null): BadgeTone {
  if (v == null) return 'neutral';
  if (v >= 70) return 'success';
  if (v >= 45) return 'warning';
  return 'danger';
}

/**
 * Phase 7.17.5 — the Company Intelligence Dashboard: the first dedicated page for the `companyintel`
 * package's data. Every section here reuses an EXISTING (or newly extended, same-phase) backend
 * endpoint — no new backend logic lives in this file. Dark-safe throughout: `company.knowledge.enabled`
 * off means the search list and every panel render an EmptyState instead of an error.
 */
export default function CompanyIntelligenceDashboard() {
  // Deep-link support: CompanyIntelPanel (job/application drawers) links here with
  // ?company=<name>&jobId=<id> so a user can jump straight to a specific company's full
  // profile, with the Candidate Fit section intact — a jobId-less visit here otherwise
  // never has a jobId to render Candidate Fit at all.
  const [searchParams] = useSearchParams();
  const deepLinkCompany = searchParams.get('company');
  const deepLinkJobId = searchParams.get('jobId');

  const [tab, setTab] = useState('overview');
  const [query, setQuery] = useState(deepLinkCompany ?? '');
  const [selected, setSelected] = useState<CompanySummary | null>(null);
  const [compareB, setCompareB] = useState<CompanySummary | null>(null);

  const search = useQuery({
    queryKey: ['company-intel', 'dashboard-search', query],
    queryFn: () => companyIntel.search(query || undefined),
    staleTime: 30_000,
    retry: false,
  });

  // Auto-select the deep-linked company once its search result arrives (once only —
  // afterwards the user's own clicks take over, matching this page's existing selection model).
  useEffect(() => {
    if (!deepLinkCompany || selected || !search.data) return;
    const match = search.data.find((c) => c.companyName.toLowerCase() === deepLinkCompany.toLowerCase()) ?? search.data[0];
    if (match) setSelected(match);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deepLinkCompany, search.data]);

  return (
    <div className="space-y-4">
      <PageHeader
        title="Company Intelligence"
        description="Deterministic company knowledge — hiring, interviews, technology, and comparison, built entirely from real platform data."
      />

      <Card className="p-3">
        <div className="relative">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            className="pl-9"
            placeholder="Search known companies…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
        {search.isLoading ? (
          <div className="mt-3 flex gap-2">
            {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-8 w-28 rounded-full" />)}
          </div>
        ) : !search.data || search.data.length === 0 ? (
          <p className="mt-3 text-xs text-muted-foreground">
            {query ? 'No matching companies found.' : 'Type to search, or leave blank to see recently updated companies.'}
          </p>
        ) : (
          <div className="mt-3 flex flex-wrap gap-2">
            {search.data.map((c) => (
              <button
                key={c.id}
                onClick={() => setSelected(c)}
                className="focus:outline-none"
              >
                <Badge tone={selected?.id === c.id ? 'primary' : 'neutral'} className="cursor-pointer">
                  <Building2 className="h-3 w-3" /> {c.companyName}
                </Badge>
              </button>
            ))}
          </div>
        )}
      </Card>

      {!selected ? (
        <EmptyState
          icon={Building2}
          title="Select a company"
          description="Search above and pick a company to see its overview, hiring intelligence, interview intelligence, technology profile, and comparisons."
        />
      ) : (
        <>
          <Tabs items={TABS} value={tab} onChange={setTab} />
          {tab === 'overview' && (
            <CompanyIntelPanel companyName={selected.companyName} jobId={deepLinkJobId} showTimeline />
          )}
          {tab === 'hiring' && <HiringTab companyId={selected.id} />}
          {tab === 'interview' && <InterviewTab companyId={selected.id} />}
          {tab === 'technology' && <TechnologyTab companyId={selected.id} />}
          {tab === 'compare' && (
            <CompareTab
              a={selected}
              b={compareB}
              options={search.data ?? []}
              onPickB={setCompareB}
            />
          )}
        </>
      )}
    </div>
  );
}

function HiringTab({ companyId }: { companyId: string }) {
  const q = useQuery({
    queryKey: ['company-intel', 'hiring', companyId],
    queryFn: () => companyIntel.hiringIntelligence(companyId),
    retry: false,
  });
  if (q.isLoading) return <Skeleton className="h-32 rounded-lg" />;
  if (!q.data) return <EmptyState icon={Users} title="Hiring intelligence not available" description="This feature may not be enabled." compact />;
  const d = q.data;
  return (
    <Card className="space-y-3 p-4">
      <div className="flex flex-wrap gap-2">
        <Badge tone={scoreTone(d.hiringProbability)}>Hiring probability: {d.hiringProbability ?? 'N/A'}</Badge>
        <Badge tone="neutral">
          Avg time to hire: {d.avgTimeToHireDays != null ? `${Math.round(d.avgTimeToHireDays)} days` : 'N/A'}
        </Badge>
      </div>
      <p className="text-xs text-muted-foreground">{d.avgTimeToHireEvidence}</p>
      <div className="grid grid-cols-2 gap-2 text-xs text-muted-foreground">
        <p><span className="font-medium text-foreground">Department hiring:</span> {d.departmentHiringNote}</p>
        <p><span className="font-medium text-foreground">Seasonal hiring:</span> {d.seasonalHiringNote}</p>
      </div>
      {d.insights.length > 0 && (
        <ul className="space-y-1">
          {d.insights.map((i) => (
            <li key={i.kind} className="text-xs text-muted-foreground">
              <span className="font-medium text-foreground">{i.headline}</span> — {i.detail}
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function InterviewTab({ companyId }: { companyId: string }) {
  const q = useQuery({
    queryKey: ['company-intel', 'interview', companyId],
    queryFn: () => companyIntel.interviewIntelligence(companyId),
    retry: false,
  });
  if (q.isLoading) return <Skeleton className="h-32 rounded-lg" />;
  if (!q.data) return <EmptyState icon={Clock} title="Interview intelligence not available" description="This feature may not be enabled." compact />;
  const d = q.data;
  if (d.interviewRounds === 0) {
    return <EmptyState title="No interview rounds recorded yet" description="Nothing has been observed for this company." compact />;
  }
  return (
    <Card className="space-y-3 p-4">
      <div className="flex flex-wrap gap-2">
        <Badge tone="neutral">{d.interviewRounds} rounds observed</Badge>
        <Badge tone={d.confidence === 'HIGH' ? 'success' : d.confidence === 'MEDIUM' ? 'warning' : 'neutral'}>
          Confidence: {d.confidence}
        </Badge>
        {d.passRate != null && <Badge tone={scoreTone(d.passRate)} title={d.passRateNote}>Pass rate: {d.passRate}%</Badge>}
        {d.avgDurationMinutes != null && <Badge tone="neutral">Avg duration: {Math.round(d.avgDurationMinutes)}m</Badge>}
        {d.avgFeedbackRating != null && <Badge tone="neutral">Avg rating: {d.avgFeedbackRating.toFixed(1)}/5</Badge>}
      </div>
      {d.passRateNote && <p className="text-xs text-muted-foreground">{d.passRateNote}</p>}
      {d.roundsByType && (
        <div className="flex flex-wrap gap-1.5">
          {Object.entries(d.roundsByType).map(([type, count]) => (
            <Badge key={type} tone="neutral">{type.replaceAll('_', ' ')}: {count}</Badge>
          ))}
        </div>
      )}
      <div className="grid grid-cols-1 gap-1 text-xs text-muted-foreground sm:grid-cols-2">
        <p>{d.behavioralRoundsNote}</p>
        <p>{d.codingRoundsNote}</p>
      </div>
      {d.frequentlyMentionedTechnologies && d.frequentlyMentionedTechnologies.length > 0 && (
        <div>
          <p className="mb-1 text-xs font-semibold text-foreground">Frequently mentioned in feedback</p>
          <div className="flex flex-wrap gap-1.5">
            {d.frequentlyMentionedTechnologies.map((t) => <Badge key={t} tone="info">{t}</Badge>)}
          </div>
        </div>
      )}
    </Card>
  );
}

function TechnologyTab({ companyId }: { companyId: string }) {
  const q = useQuery({
    queryKey: ['company-intel', 'technology-taxonomy', companyId],
    queryFn: () => companyIntel.technologyTaxonomy(companyId),
    retry: false,
  });
  if (q.isLoading) return <Skeleton className="h-32 rounded-lg" />;
  if (!q.data) return <EmptyState icon={Cpu} title="Technology intelligence not available" description="This feature may not be enabled." compact />;
  const categories = Object.entries(q.data.byCategory).filter(([, items]) => items.length > 0);
  if (categories.length === 0) {
    return <EmptyState title="No technology signals observed yet" description="No graph edges recorded for this company." compact />;
  }
  return (
    <Card className="space-y-3 p-4">
      <p className="text-xs text-muted-foreground">{q.data.totalEdges} technology/skill signals classified. {q.data.growthDeclineNote}</p>
      {categories.map(([category, items]) => (
        <div key={category}>
          <p className="mb-1 text-xs font-semibold text-foreground">{category.replaceAll('_', ' ')}</p>
          <div className="flex flex-wrap gap-1.5">
            {items.map((item) => (
              <Badge key={item.target} tone="neutral">{item.target} ({item.weight})</Badge>
            ))}
          </div>
        </div>
      ))}
    </Card>
  );
}

function CompareTab({
  a, b, options, onPickB,
}: {
  a: CompanySummary; b: CompanySummary | null; options: CompanySummary[]; onPickB: (c: CompanySummary) => void;
}) {
  const q = useQuery({
    queryKey: ['company-intel', 'compare', a.id, b?.id],
    queryFn: () => companyIntel.compare(a.id, b!.id),
    enabled: !!b,
    retry: false,
  });

  return (
    <Card className="space-y-3 p-4">
      <div className="flex items-center gap-2 text-sm">
        <GitCompare className="h-4 w-4 text-primary" />
        <span className="font-medium text-foreground">{a.companyName}</span>
        <span className="text-muted-foreground">vs</span>
        {b ? (
          <Badge tone="primary">{b.companyName}</Badge>
        ) : (
          <span className="text-xs text-muted-foreground">pick a company below</span>
        )}
      </div>
      <div className="flex flex-wrap gap-1.5">
        {options.filter((o) => o.id !== a.id).map((o) => (
          <button key={o.id} onClick={() => onPickB(o)} className="focus:outline-none">
            <Badge tone={b?.id === o.id ? 'primary' : 'neutral'} className="cursor-pointer">{o.companyName}</Badge>
          </button>
        ))}
      </div>
      {q.data && (
        <>
          <Badge tone={scoreTone(q.data.similarity)}>{q.data.similarity}% similar</Badge>
          <div className="space-y-2">
            {Object.entries(q.data.knowledgeDiff).map(([section, values]) => (
              <div key={section} className="grid grid-cols-2 gap-3 rounded-lg border border-border p-2 text-xs">
                <div>
                  <p className="font-medium text-foreground">{section} — {a.companyName}</p>
                  <p className="text-muted-foreground">{values[0] ?? '—'}</p>
                </div>
                <div>
                  <p className="font-medium text-foreground">{section} — {b?.companyName}</p>
                  <p className="text-muted-foreground">{values[1] ?? '—'}</p>
                </div>
              </div>
            ))}
          </div>
        </>
      )}
    </Card>
  );
}
