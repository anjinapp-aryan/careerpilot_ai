import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { motion } from 'framer-motion';
import {
  Bookmark,
  Building2,
  CalendarDays,
  DollarSign,
  ExternalLink,
  Filter,
  MapPin,
  Plus,
  Search,
  Send,
  SlidersHorizontal,
  Settings2,
} from 'lucide-react';
import { api } from '@/lib/api';
import { PageHeader } from '@/components/common/PageHeader';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Input, Label, Textarea } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { Tabs } from '@/components/ui/tabs';
import { useToast } from '@/components/ui/toast';
import {
  Dialog,
  DialogBody,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '@/components/ui/dialog';
import { cn } from '@/lib/cn';
import { RecommendedJobs } from '@/components/jobs/RecommendedJobs';
import { CandidateProfileCard } from '@/components/jobs/CandidateProfileCard';
import { PreferencesDialog } from '@/components/jobs/PreferencesDialog';
import { JobBadges } from '@/components/jobs/JobBadges';
import { trackJobEvent } from '@/lib/jobTelemetry';
import type { Application, CandidatePreferences, Job, JobsPage } from '@/types/workflow';

type JobsTab = 'recommended' | 'domestic' | 'international' | 'saved' | 'applied' | 'browse';

const TAB_ITEMS: { value: JobsTab; label: string }[] = [
  { value: 'recommended', label: 'Recommended' },
  { value: 'domestic', label: 'Domestic' },
  { value: 'international', label: 'International' },
  { value: 'saved', label: 'Saved' },
  { value: 'applied', label: 'Applied' },
  { value: 'browse', label: 'Browse' },
];

const EMPTY_DRAFT = { title: '', company: '', location: '', description: '', salaryRange: '' };

function companyColor(name: string) {
  const palette = ['bg-primary/10 text-primary', 'bg-secondary/10 text-secondary', 'bg-success/10 text-success', 'bg-warning/10 text-warning'];
  let h = 0;
  for (const c of name) h = (h * 31 + c.charCodeAt(0)) % palette.length;
  return palette[h];
}

export default function Jobs() {
  const qc = useQueryClient();
  const { toast } = useToast();
  const [q, setQ] = useState('');
  const [location, setLocation] = useState('');
  const [remoteOnly, setRemoteOnly] = useState(false);
  const [company, setCompany] = useState('');
  const [showNew, setShowNew] = useState(false);
  const [showFilters, setShowFilters] = useState(false);
  const [draft, setDraft] = useState(EMPTY_DRAFT);
  const [tab, setTab] = useState<JobsTab>('recommended');
  const [homeCountry, setHomeCountry] = useState('India');
  const [showPreferences, setShowPreferences] = useState(false);
  // International facets
  const [remoteTypeFilter, setRemoteTypeFilter] = useState('');
  const [sponsorshipFilter, setSponsorshipFilter] = useState(false);
  const [relocationFilter, setRelocationFilter] = useState(false);
  const [countrySearch, setCountrySearch] = useState('');

  const { data, isLoading } = useQuery<JobsPage>({
    queryKey: ['jobs', q],
    queryFn: async () => (await api.get('/api/jobs', { params: { q } })).data,
    enabled: tab === 'browse',
  });

  const isDiscoverTab = tab === 'domestic' || tab === 'international';
  const discoverParams =
    tab === 'international'
      ? {
          scope: tab,
          country: homeCountry,
          remoteType: remoteTypeFilter || undefined,
          sponsorship: sponsorshipFilter || undefined,
          relocation: relocationFilter || undefined,
          q: countrySearch || undefined,
        }
      : { scope: tab, country: homeCountry };
  const { data: discoveredPage, isLoading: discoveredLoading } = useQuery<JobsPage>({
    queryKey: ['jobs', 'discovered', tab, homeCountry, remoteTypeFilter, sponsorshipFilter, relocationFilter, countrySearch],
    queryFn: async () => (await api.get('/api/jobs/discovered', { params: discoverParams })).data,
    enabled: isDiscoverTab,
  });
  const discoveredJobs = discoveredPage?.content ?? [];

  // Home country defaults to the user's first saved preferred country (instead of a hardcoded
  // 'India'), applied once so a manual chip selection is never overridden on a later refetch.
  const homeCountryInitialized = useRef(false);
  const { data: preferences } = useQuery<CandidatePreferences>({
    queryKey: ['candidate', 'preferences'],
    queryFn: async () => (await api.get('/api/candidate/preferences')).data,
  });
  useEffect(() => {
    if (homeCountryInitialized.current) return;
    // Home country is server-authoritative for Domestic; mirror it in local state for display +
    // the legacy country param. Prefer the explicit home country, else the first preferred country.
    const resolved = preferences?.homeCountry || preferences?.preferredCountries?.[0];
    if (resolved) {
      setHomeCountry(resolved);
      homeCountryInitialized.current = true;
    }
  }, [preferences]);

  const preferredCountries = preferences?.preferredCountries ?? [];

  // Browse "more opportunities": global discovered pool minus high-confidence recommendations.
  const { data: poolPage } = useQuery<JobsPage>({
    queryKey: ['jobs', 'pool'],
    queryFn: async () => (await api.get('/api/jobs/pool')).data,
    enabled: tab === 'browse',
  });
  const poolJobs = poolPage?.content ?? [];

  const { data: applications } = useQuery<Application[]>({
    queryKey: ['applications'],
    queryFn: async () => (await api.get('/api/applications')).data,
    enabled: tab === 'saved' || tab === 'applied',
  });

  const { data: allJobsPage } = useQuery<JobsPage>({
    queryKey: ['jobs', ''],
    queryFn: async () => (await api.get('/api/jobs')).data,
    enabled: tab === 'saved' || tab === 'applied',
  });

  const jobMap = useMemo(() => {
    const m = new Map<string, Job>();
    (allJobsPage?.content ?? []).forEach((j) => m.set(j.id, j));
    return m;
  }, [allJobsPage]);

  const savedJobs = useMemo(
    () =>
      (applications ?? [])
        .filter((a) => a.status === 'SAVED')
        .map((a) => jobMap.get(a.jobId))
        .filter((j): j is Job => Boolean(j)),
    [applications, jobMap],
  );
  const appliedApplications = useMemo(
    () => (applications ?? []).filter((a) => a.status !== 'SAVED'),
    [applications],
  );

  const create = useMutation({
    mutationFn: async () => (await api.post('/api/jobs', draft)).data as Job,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['jobs'] });
      setShowNew(false);
      setDraft(EMPTY_DRAFT);
      toast({ variant: 'success', title: 'Job added' });
    },
    onError: () => toast({ variant: 'error', title: 'Could not add job' }),
  });

  const track = useMutation({
    mutationFn: async ({ jobId, status }: { jobId: string; status: string }) =>
      (await api.post('/api/applications', { jobId, status })).data,
    onSuccess: (_d, v) => {
      qc.invalidateQueries({ queryKey: ['applications'] });
      qc.invalidateQueries({ queryKey: ['dashboard'] });
      toast({
        variant: 'success',
        title: v.status === 'SAVED' ? 'Job saved' : 'Application created',
        description: 'Track it from the Applications board.',
      });
    },
    onError: () => toast({ variant: 'error', title: 'Action failed' }),
  });

  // Telemetry-wrapped actions for the discovered/saved/browse/pool cards.
  const applyJob = (jobId: string) => {
    trackJobEvent('apply', { jobId });
    track.mutate({ jobId, status: 'APPLIED' });
  };
  const saveJob = (jobId: string) => {
    trackJobEvent('save', { jobId });
    track.mutate({ jobId, status: 'SAVED' });
  };

  const jobs = data?.content ?? [];
  const filtered = useMemo(
    () =>
      jobs.filter((j) => {
        if (location && !(j.location ?? '').toLowerCase().includes(location.toLowerCase())) return false;
        if (company && !j.company.toLowerCase().includes(company.toLowerCase())) return false;
        if (remoteOnly && !/remote/i.test(`${j.location ?? ''} ${j.title}`)) return false;
        return true;
      }),
    [jobs, location, company, remoteOnly],
  );

  const activeFilters = [location, company].filter(Boolean).length + (remoteOnly ? 1 : 0);

  const filterPanel = (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <h2 className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <SlidersHorizontal className="h-4 w-4" /> Filters
        </h2>
        {activeFilters > 0 && (
          <button
            onClick={() => {
              setLocation('');
              setCompany('');
              setRemoteOnly(false);
            }}
            className="text-xs font-medium text-primary hover:underline"
          >
            Clear
          </button>
        )}
      </div>
      <div>
        <Label>Location</Label>
        <Input value={location} onChange={(e) => setLocation(e.target.value)} placeholder="e.g. Berlin, US" />
      </div>
      <div>
        <Label>Company</Label>
        <Input value={company} onChange={(e) => setCompany(e.target.value)} placeholder="e.g. Stripe" />
      </div>
      <label className="flex cursor-pointer items-center justify-between rounded-lg border border-border bg-card px-3 py-2.5">
        <span className="text-sm font-medium text-foreground">Remote only</span>
        <button
          type="button"
          role="switch"
          aria-checked={remoteOnly}
          onClick={() => setRemoteOnly((v) => !v)}
          className={cn('relative h-5 w-9 rounded-full transition-colors', remoteOnly ? 'bg-primary' : 'bg-muted')}
        >
          <span
            className={cn(
              'absolute top-0.5 h-4 w-4 rounded-full bg-white shadow transition-transform',
              remoteOnly ? 'translate-x-4' : 'translate-x-0.5',
            )}
          />
        </button>
      </label>
    </div>
  );

  return (
    <div className="space-y-6">
      <PageHeader
        title="Jobs"
        description="Discover roles, track openings, and push them into your pipeline."
        actions={
          <>
            <Button variant="outline" onClick={() => setShowPreferences(true)}>
              <Settings2 className="h-4 w-4" /> Preferences
            </Button>
            {tab === 'browse' && (
              <>
                <Button variant="outline" className="lg:hidden" onClick={() => setShowFilters((v) => !v)}>
                  <Filter className="h-4 w-4" /> Filters{activeFilters ? ` (${activeFilters})` : ''}
                </Button>
                <Button onClick={() => setShowNew(true)}>
                  <Plus className="h-4 w-4" /> Add job
                </Button>
              </>
            )}
          </>
        }
      />

      <Tabs items={TAB_ITEMS} value={tab} onChange={(v) => setTab(v as JobsTab)} />

      {tab === 'recommended' && (
        <div className="space-y-4">
          <CandidateProfileCard />
          <RecommendedJobs
            onApply={(jobId) => track.mutate({ jobId, status: 'APPLIED' })}
            onSave={(jobId) => track.mutate({ jobId, status: 'SAVED' })}
            busy={track.isPending}
          />
        </div>
      )}

      {isDiscoverTab && (
        <div className="space-y-4">
          {tab === 'domestic' && (
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-sm font-medium text-muted-foreground">Home country</span>
              <span className="rounded-full border border-primary bg-primary/10 px-3 py-1 text-xs font-medium text-primary">
                {homeCountry}
              </span>
              <button
                onClick={() => setShowPreferences(true)}
                className="text-xs font-medium text-primary hover:underline"
              >
                Change in Preferences
              </button>
            </div>
          )}
          {tab === 'international' && (
            <div className="flex flex-wrap items-center gap-2">
              {preferredCountries.length > 0 && (
                <div className="flex flex-wrap items-center gap-1.5">
                  <span className="text-sm font-medium text-muted-foreground">Preferred</span>
                  {preferredCountries.map((c) => (
                    <span
                      key={c}
                      className="rounded-full border border-border bg-muted/50 px-2.5 py-1 text-xs font-medium text-foreground"
                    >
                      {c}
                    </span>
                  ))}
                </div>
              )}
              <div className="relative">
                <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
                <Input
                  value={countrySearch}
                  onChange={(e) => setCountrySearch(e.target.value)}
                  placeholder="Search country / role…"
                  className="h-8 w-52 pl-8 text-xs"
                />
              </div>
              {(['REMOTE', 'HYBRID', 'ONSITE'] as const).map((rt) => (
                <button
                  key={rt}
                  onClick={() => setRemoteTypeFilter((v) => (v === rt ? '' : rt))}
                  className={cn(
                    'rounded-full border px-3 py-1 text-xs font-medium transition-colors',
                    remoteTypeFilter === rt
                      ? 'border-primary bg-primary/10 text-primary'
                      : 'border-border text-muted-foreground hover:bg-muted',
                  )}
                >
                  {rt.charAt(0) + rt.slice(1).toLowerCase()}
                </button>
              ))}
              <button
                onClick={() => setSponsorshipFilter((v) => !v)}
                className={cn(
                  'rounded-full border px-3 py-1 text-xs font-medium transition-colors',
                  sponsorshipFilter ? 'border-primary bg-primary/10 text-primary' : 'border-border text-muted-foreground hover:bg-muted',
                )}
              >
                Visa Sponsorship
              </button>
              <button
                onClick={() => setRelocationFilter((v) => !v)}
                className={cn(
                  'rounded-full border px-3 py-1 text-xs font-medium transition-colors',
                  relocationFilter ? 'border-primary bg-primary/10 text-primary' : 'border-border text-muted-foreground hover:bg-muted',
                )}
              >
                Relocation Support
              </button>
            </div>
          )}
          <p className="text-sm text-muted-foreground">
            {discoveredLoading
              ? 'Loading roles…'
              : tab === 'domestic'
                ? `${discoveredJobs.length} role${discoveredJobs.length === 1 ? '' : 's'} in ${homeCountry}`
                : `${discoveredJobs.length} role${discoveredJobs.length === 1 ? '' : 's'} in your preferred countries`}
          </p>
          {discoveredLoading ? (
            <div className="space-y-4">
              {Array.from({ length: 4 }).map((_, i) => (
                <Skeleton key={i} className="h-36 rounded-xl" />
              ))}
            </div>
          ) : discoveredJobs.length === 0 ? (
            <EmptyState
              icon={Building2}
              title="No discovered jobs yet"
              description="Daily job discovery pulls real roles from RemoteOK, Arbeitnow, Adzuna and Jooble. Once a run completes they appear here."
            />
          ) : (
            <div className="space-y-4">
              {discoveredJobs.map((job, i) => (
                <JobCard
                  key={job.id}
                  job={job}
                  index={i}
                  onSave={() => saveJob(job.id)}
                  onApply={() => applyJob(job.id)}
                  busy={track.isPending}
                />
              ))}
            </div>
          )}
        </div>
      )}

      {tab === 'saved' && (
        savedJobs.length === 0 ? (
          <EmptyState
            icon={Bookmark}
            title="No saved jobs yet"
            description="Save a role from Recommended or Browse to keep track of it here."
          />
        ) : (
          <div className="space-y-4">
            {savedJobs.map((job, i) => (
              <JobCard
                key={job.id}
                job={job}
                index={i}
                onSave={() => saveJob(job.id)}
                onApply={() => applyJob(job.id)}
                busy={track.isPending}
              />
            ))}
          </div>
        )
      )}

      {tab === 'applied' && (
        appliedApplications.length === 0 ? (
          <EmptyState
            icon={Send}
            title="No applications yet"
            description="Apply to a role from Recommended or Browse to start tracking it here."
          />
        ) : (
          <div className="space-y-4">
            {appliedApplications.map((a) => {
              const job = jobMap.get(a.jobId);
              if (!job) return null;
              return (
                <Card key={a.id} className="p-5">
                  <div className="flex flex-wrap items-start justify-between gap-2">
                    <div>
                      <h3 className="text-base font-semibold text-foreground">{job.title}</h3>
                      <p className="flex items-center gap-1.5 text-sm text-muted-foreground">
                        <Building2 className="h-3.5 w-3.5" /> {job.company}
                      </p>
                    </div>
                    <Badge tone="primary">{a.status}</Badge>
                  </div>
                </Card>
              );
            })}
          </div>
        )
      )}

      {tab === 'browse' && (
      <div className="grid gap-6 lg:grid-cols-[260px_1fr]">
        {/* Left: filters */}
        <aside className="hidden lg:block">
          <Card className="sticky top-20 p-5">{filterPanel}</Card>
        </aside>
        {showFilters && (
          <Card className="p-5 lg:hidden">{filterPanel}</Card>
        )}

        {/* Right: results */}
        <div className="space-y-4">
          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder="Search by title or company…"
              className="h-11 pl-9"
            />
          </div>

          <p className="text-sm text-muted-foreground">
            {isLoading ? 'Searching…' : `${filtered.length} role${filtered.length === 1 ? '' : 's'} found`}
          </p>

          {isLoading ? (
            <div className="space-y-4">
              {Array.from({ length: 4 }).map((_, i) => (
                <Skeleton key={i} className="h-36 rounded-xl" />
              ))}
            </div>
          ) : filtered.length === 0 ? (
            <EmptyState
              icon={Building2}
              title="No jobs found"
              description="Adjust your filters or add a job posting to start tracking it."
              action={
                <Button onClick={() => setShowNew(true)}>
                  <Plus className="h-4 w-4" /> Add job
                </Button>
              }
            />
          ) : (
            <div className="space-y-4">
              {filtered.map((job, i) => (
                <JobCard
                  key={job.id}
                  job={job}
                  index={i}
                  onSave={() => saveJob(job.id)}
                  onApply={() => applyJob(job.id)}
                  busy={track.isPending}
                />
              ))}
            </div>
          )}

          {/* More opportunities: discovered roles below your high-confidence match bar. */}
          {poolJobs.length > 0 && (
            <div className="space-y-4 pt-2">
              <div className="flex items-center gap-2">
                <h2 className="text-sm font-semibold text-foreground">More opportunities</h2>
                <Badge tone="neutral">{poolJobs.length}</Badge>
              </div>
              <p className="text-xs text-muted-foreground">
                Discovered roles that didn’t clear your 70% match bar — still worth a look.
              </p>
              {poolJobs.map((job, i) => (
                <JobCard
                  key={job.id}
                  job={job}
                  index={i}
                  onSave={() => saveJob(job.id)}
                  onApply={() => applyJob(job.id)}
                  busy={track.isPending}
                />
              ))}
            </div>
          )}
        </div>
      </div>
      )}

      <PreferencesDialog open={showPreferences} onOpenChange={setShowPreferences} />

      {/* Add job dialog */}
      <Dialog open={showNew} onOpenChange={setShowNew} size="lg">
        <DialogHeader onClose={() => setShowNew(false)}>
          <DialogTitle>Add a job</DialogTitle>
          <DialogDescription>Track a role you found elsewhere.</DialogDescription>
        </DialogHeader>
        <DialogBody className="space-y-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <Label>Title</Label>
              <Input value={draft.title} onChange={(e) => setDraft({ ...draft, title: e.target.value })} placeholder="Senior Software Engineer" />
            </div>
            <div>
              <Label>Company</Label>
              <Input value={draft.company} onChange={(e) => setDraft({ ...draft, company: e.target.value })} placeholder="Acme Inc." />
            </div>
            <div>
              <Label>Location</Label>
              <Input value={draft.location} onChange={(e) => setDraft({ ...draft, location: e.target.value })} placeholder="Remote, US" />
            </div>
            <div>
              <Label>Salary range</Label>
              <Input value={draft.salaryRange} onChange={(e) => setDraft({ ...draft, salaryRange: e.target.value })} placeholder="$150k – $190k" />
            </div>
          </div>
          <div>
            <Label>Description</Label>
            <Textarea
              value={draft.description}
              onChange={(e) => setDraft({ ...draft, description: e.target.value })}
              placeholder="Paste the job description…"
              className="min-h-[120px]"
            />
          </div>
        </DialogBody>
        <DialogFooter>
          <Button variant="ghost" onClick={() => setShowNew(false)}>
            Cancel
          </Button>
          <Button
            onClick={() => create.mutate()}
            loading={create.isPending}
            disabled={!draft.title || !draft.company || !draft.description}
          >
            Save job
          </Button>
        </DialogFooter>
      </Dialog>
    </div>
  );
}

interface JobCardProps {
  job: Job;
  index: number;
  onSave: () => void;
  onApply: () => void;
  busy: boolean;
}

function JobCard({ job, index, onSave, onApply, busy }: JobCardProps) {
  const isRemote = /remote/i.test(`${job.location ?? ''} ${job.title}`);
  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: index * 0.04 }}
    >
      <Card className="p-5 transition-shadow hover:shadow-md">
        <div className="flex gap-4">
          <span className={cn('flex h-12 w-12 shrink-0 items-center justify-center rounded-xl text-base font-semibold', companyColor(job.company))}>
            {job.company.slice(0, 2).toUpperCase()}
          </span>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-start justify-between gap-2">
              <div className="min-w-0">
                <h3 className="truncate text-base font-semibold text-foreground">{job.title}</h3>
                <p className="flex items-center gap-1.5 text-sm text-muted-foreground">
                  <Building2 className="h-3.5 w-3.5" /> {job.company}
                </p>
              </div>
              {isRemote && !job.remoteType && <Badge tone="success">Remote</Badge>}
            </div>

            <JobBadges job={job} className="mt-2.5" />

            <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-xs text-muted-foreground">
              {job.location && (
                <span className="flex items-center gap-1">
                  <MapPin className="h-3.5 w-3.5" /> {job.location}
                </span>
              )}
              {job.country && !job.location?.toLowerCase().includes(job.country.toLowerCase()) && (
                <span>{job.country}</span>
              )}
              {job.requiredExperience != null && <span>{job.requiredExperience}+ yrs exp</span>}
              {job.salaryRange && (
                <span className="flex items-center gap-1">
                  <DollarSign className="h-3.5 w-3.5" /> {job.salaryRange}
                </span>
              )}
              {job.postedAt && (
                <span className="flex items-center gap-1">
                  <CalendarDays className="h-3.5 w-3.5" /> {new Date(job.postedAt).toLocaleDateString()}
                </span>
              )}
            </div>

            {job.description && (
              <p className="mt-3 line-clamp-2 text-sm text-muted-foreground">{job.description}</p>
            )}

            <div className="mt-4 flex items-center gap-2">
              <Button size="sm" onClick={onApply} disabled={busy}>
                <Send className="h-3.5 w-3.5" /> Apply
              </Button>
              <Button size="sm" variant="outline" onClick={onSave} disabled={busy}>
                <Bookmark className="h-3.5 w-3.5" /> Save
              </Button>
              {(job.sourceUrl || job.externalUrl) && (
                <a
                  href={(job.sourceUrl || job.externalUrl) as string}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  <Button size="sm" variant="ghost">
                    <ExternalLink className="h-3.5 w-3.5" /> View posting
                  </Button>
                </a>
              )}
            </div>
          </div>
        </div>
      </Card>
    </motion.div>
  );
}
