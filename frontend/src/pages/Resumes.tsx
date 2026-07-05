import { useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import {
  ChevronDown,
  ChevronUp,
  Clock,
  Download,
  FileText,
  Grid2x2,
  History,
  Layers,
  List,
  MoreVertical,
  Search,
  Sparkles,
  Trash2,
  UploadCloud,
} from 'lucide-react';
import { api } from '@/lib/api';
import { PageHeader } from '@/components/common/PageHeader';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { useToast } from '@/components/ui/toast';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { cn } from '@/lib/cn';
import type { Resume, ResumeVersion, TailoredResumeVersion } from '@/types/workflow';

type SortKey = 'recent' | 'name' | 'score';

function formatBytes(bytes?: number | null): string {
  if (!bytes) return '—';
  const units = ['B', 'KB', 'MB'];
  let n = bytes;
  let u = 0;
  while (n >= 1024 && u < units.length - 1) {
    n /= 1024;
    u++;
  }
  return `${n.toFixed(n < 10 && u > 0 ? 1 : 0)} ${units[u]}`;
}

function scoreTone(s?: number | null): 'success' | 'warning' | 'danger' | 'neutral' {
  if (s == null) return 'neutral';
  if (s >= 80) return 'success';
  if (s >= 60) return 'warning';
  return 'danger';
}

export default function Resumes() {
  const qc = useQueryClient();
  const { toast } = useToast();
  const [view, setView] = useState<'grid' | 'list'>('grid');
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState<SortKey>('recent');
  const [dragging, setDragging] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const { data = [], isLoading } = useQuery<Resume[]>({
    queryKey: ['resumes'],
    queryFn: async () => (await api.get('/api/resumes')).data,
  });

  const upload = useMutation({
    mutationFn: async (file: File) => {
      const fd = new FormData();
      fd.append('file', file);
      const { data } = await api.post('/api/resumes', fd, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      return data as Resume;
    },
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ['resumes'] });
      qc.invalidateQueries({ queryKey: ['dashboard'] });
      toast({ variant: 'success', title: 'Resume uploaded', description: r.filename });
    },
    onError: () =>
      toast({ variant: 'error', title: 'Upload failed', description: 'Please try again.' }),
  });

  function handleFiles(files: FileList | null) {
    const file = files?.[0];
    if (file) upload.mutate(file);
  }

  const filtered = useMemo(() => {
    let list = data.filter((r) => r.filename.toLowerCase().includes(query.trim().toLowerCase()));
    list = [...list].sort((a, b) => {
      if (sort === 'name') return a.filename.localeCompare(b.filename);
      if (sort === 'score') return (b.resumeScore ?? -1) - (a.resumeScore ?? -1);
      return new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime();
    });
    return list;
  }, [data, query, sort]);

  return (
    <div className="space-y-6">
      <PageHeader
        title="Resume Library"
        description="Manage your resumes, track ATS scores, and keep versions organized."
        actions={
          <Button onClick={() => inputRef.current?.click()} loading={upload.isPending}>
            {!upload.isPending && <UploadCloud className="h-4 w-4" />}
            Upload resume
          </Button>
        }
      />
      <input
        ref={inputRef}
        type="file"
        className="hidden"
        accept=".pdf,.doc,.docx,.txt"
        onChange={(e) => handleFiles(e.target.files)}
      />

      {/* Dropzone */}
      <div
        onDragOver={(e) => {
          e.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragging(false);
          handleFiles(e.dataTransfer.files);
        }}
        onClick={() => inputRef.current?.click()}
        className={cn(
          'flex cursor-pointer flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed p-8 text-center transition-colors',
          dragging ? 'border-primary bg-primary/5' : 'border-border bg-muted/20 hover:border-primary/40',
        )}
      >
        <span className="flex h-11 w-11 items-center justify-center rounded-full bg-primary/10 text-primary">
          <UploadCloud className="h-5 w-5" />
        </span>
        <p className="text-sm font-medium text-foreground">
          Drag & drop your resume, or <span className="text-primary">browse</span>
        </p>
        <p className="text-xs text-muted-foreground">PDF, DOCX or TXT — up to 10MB</p>
      </div>

      {/* Toolbar */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="relative w-full sm:max-w-xs">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search resumes…"
            className="pl-9"
          />
        </div>
        <div className="flex items-center gap-2">
          <select
            value={sort}
            onChange={(e) => setSort(e.target.value as SortKey)}
            className="h-10 rounded-lg border border-input bg-card px-3 text-sm text-foreground focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <option value="recent">Most recent</option>
            <option value="name">Name (A–Z)</option>
            <option value="score">ATS score</option>
          </select>
          <div className="flex rounded-lg border border-border p-0.5">
            <button
              onClick={() => setView('grid')}
              aria-label="Grid view"
              className={cn('flex h-8 w-8 items-center justify-center rounded-md transition-colors', view === 'grid' ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground')}
            >
              <Grid2x2 className="h-4 w-4" />
            </button>
            <button
              onClick={() => setView('list')}
              aria-label="List view"
              className={cn('flex h-8 w-8 items-center justify-center rounded-md transition-colors', view === 'list' ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground')}
            >
              <List className="h-4 w-4" />
            </button>
          </div>
        </div>
      </div>

      {/* Phase 4E — Phase 2D.1 async tailoring engine history, across all jobs. Dark by default. */}
      <TailoringHistoryPanel />

      {/* Content */}
      {isLoading ? (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="h-44 rounded-xl" />
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <EmptyState
          icon={FileText}
          title={data.length === 0 ? 'No resumes yet' : 'No matches'}
          description={
            data.length === 0
              ? 'Upload your first resume to start optimizing for ATS and applying to jobs.'
              : 'Try a different search term.'
          }
          action={
            data.length === 0 ? (
              <Button onClick={() => inputRef.current?.click()}>
                <UploadCloud className="h-4 w-4" /> Upload resume
              </Button>
            ) : undefined
          }
        />
      ) : view === 'grid' ? (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {filtered.map((r, i) => (
            <ResumeCard key={r.id} resume={r} index={i} />
          ))}
        </div>
      ) : (
        <Card className="overflow-hidden">
          <div className="divide-y divide-border">
            {filtered.map((r) => (
              <ResumeRow key={r.id} resume={r} />
            ))}
          </div>
        </Card>
      )}
    </div>
  );
}

function QuickActions({ resume }: { resume: Resume }) {
  const navigate = useNavigate();
  return (
    <DropdownMenu>
      <DropdownMenuTrigger className="rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground">
        <MoreVertical className="h-4 w-4" />
      </DropdownMenuTrigger>
      <DropdownMenuContent>
        <DropdownMenuItem onSelect={() => navigate(`/resumes/${resume.id}/optimize`)}>
          <Sparkles className="h-4 w-4 text-muted-foreground" /> Optimize with AI
        </DropdownMenuItem>
        <DropdownMenuItem>
          <Download className="h-4 w-4 text-muted-foreground" /> Download
        </DropdownMenuItem>
        <DropdownMenuItem tone="danger">
          <Trash2 className="h-4 w-4" /> Delete
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function ResumeCard({ resume, index }: { resume: Resume; index: number }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: index * 0.04 }}
      whileHover={{ y: -3 }}
    >
      <Card className="flex h-full flex-col p-5 transition-shadow hover:shadow-md">
        <div className="flex items-start justify-between">
          <span className="flex h-11 w-11 items-center justify-center rounded-xl bg-primary/10 text-primary">
            <FileText className="h-5 w-5" />
          </span>
          <QuickActions resume={resume} />
        </div>
        <h3 className="mt-4 truncate text-sm font-semibold text-foreground" title={resume.filename}>
          {resume.filename}
        </h3>
        <p className="mt-0.5 flex items-center gap-1 text-xs text-muted-foreground">
          <Clock className="h-3 w-3" /> {new Date(resume.createdAt).toLocaleDateString()} ·{' '}
          {formatBytes(resume.sizeBytes)}
        </p>
        <div className="mt-4 flex items-center justify-between border-t border-border pt-4">
          <div className="flex items-center gap-2">
            <Badge tone={scoreTone(resume.resumeScore)}>
              ATS {resume.resumeScore ?? '—'}
            </Badge>
            <Badge tone="neutral">Ready</Badge>
          </div>
        </div>
        <ResumeVersionsStrip resumeId={resume.id} />
      </Card>
    </motion.div>
  );
}

/**
 * Lazy ATS-progression strip: v1 → vN with each version's post-optimization ATS score.
 * Fetches `/api/resumes/{id}/versions` only when expanded, and degrades to a quiet
 * "no optimized versions yet" line when the resume has never been through the pipeline.
 */
function ResumeVersionsStrip({ resumeId }: { resumeId: string }) {
  const [open, setOpen] = useState(false);
  const { data = [], isLoading, isError } = useQuery<ResumeVersion[]>({
    queryKey: ['resume-versions', resumeId],
    queryFn: async () => (await api.get(`/api/resumes/${resumeId}/versions`)).data,
    enabled: open,
    retry: false,
  });

  // Oldest → newest so the ATS trend reads left-to-right.
  const ordered = [...data].sort((a, b) => a.versionNumber - b.versionNumber);

  return (
    <div className="mt-3 border-t border-border pt-3">
      <button
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center justify-between text-xs font-medium text-muted-foreground transition-colors hover:text-foreground"
      >
        <span className="flex items-center gap-1.5">
          <Layers className="h-3.5 w-3.5" /> Version history
        </span>
        {open ? <ChevronUp className="h-3.5 w-3.5" /> : <ChevronDown className="h-3.5 w-3.5" />}
      </button>
      {open && (
        <div className="mt-2">
          {isLoading ? (
            <Skeleton className="h-10 w-full rounded-md" />
          ) : isError || ordered.length === 0 ? (
            <p className="text-xs text-muted-foreground">No optimized versions yet — run AI optimization to build history.</p>
          ) : (
            <div className="flex flex-wrap items-center gap-1.5">
              {ordered.map((v, i) => (
                <div key={v.id} className="flex items-center gap-1.5">
                  <div className="flex flex-col items-center rounded-md border border-border bg-muted/30 px-2 py-1">
                    <span className="text-[10px] font-medium text-muted-foreground">v{v.versionNumber}</span>
                    <span className={cn('text-xs font-semibold tabular-nums', {
                      'text-success': (v.atsAfter ?? 0) >= 80,
                      'text-warning': (v.atsAfter ?? 0) >= 60 && (v.atsAfter ?? 0) < 80,
                      'text-danger': (v.atsAfter ?? 100) < 60,
                    })}>
                      {v.atsAfter ?? '—'}
                    </span>
                  </div>
                  {i < ordered.length - 1 && <span className="h-px w-3 bg-border" />}
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * Phase 4E — surfaces the Phase 2D.1 async Resume Tailoring engine's history across all jobs
 * (GET /api/resume/tailored/history). Dark-tolerant: 404/empty renders "not enabled yet", not
 * an error, since RESUME_TAILORING_ENABLED defaults to false.
 */
function TailoringHistoryPanel() {
  const { data, isLoading, isError } = useQuery<{ versions: TailoredResumeVersion[] } | null>({
    queryKey: ['resume', 'tailored-history'],
    queryFn: async () => {
      try {
        return (await api.get('/api/resume/tailored/history')).data;
      } catch {
        return null;
      }
    },
    retry: false,
    staleTime: 30_000,
  });
  const versions = data?.versions ?? [];

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between pb-2">
        <CardTitle className="flex items-center gap-2 text-base">
          <History className="h-4 w-4 text-muted-foreground" /> Tailoring history
        </CardTitle>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <Skeleton className="h-16 w-full" />
        ) : isError || versions.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No tailored resume versions yet — the AI tailoring engine generates one per job once enabled.
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted-foreground">
                  <th className="py-2 pr-4">Version</th>
                  <th className="py-2 pr-4">Job</th>
                  <th className="py-2 pr-4">ATS before → after</th>
                  <th className="py-2 pr-4">Status</th>
                  <th className="py-2 pr-4">Created</th>
                </tr>
              </thead>
              <tbody>
                {versions.map((v) => (
                  <tr key={v.id} className="border-b border-border/50">
                    <td className="py-2 pr-4 font-medium">{v.version}</td>
                    <td className="py-2 pr-4 font-mono text-xs text-muted-foreground">{v.jobId.slice(0, 8)}</td>
                    <td className="py-2 pr-4 tabular-nums">
                      {v.atsBefore ?? '—'} → {v.atsAfter ?? '—'}
                    </td>
                    <td className="py-2 pr-4">
                      <Badge tone={v.status === 'COMPLETED' ? 'success' : v.status === 'FAILED' ? 'danger' : 'neutral'}>
                        {v.status}
                      </Badge>
                    </td>
                    <td className="py-2 pr-4 text-muted-foreground">{new Date(v.createdAt).toLocaleDateString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function ResumeRow({ resume }: { resume: Resume }) {
  return (
    <div className="flex items-center gap-4 px-4 py-3 transition-colors hover:bg-muted/40">
      <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
        <FileText className="h-4 w-4" />
      </span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium text-foreground">{resume.filename}</p>
        <p className="text-xs text-muted-foreground">
          {new Date(resume.createdAt).toLocaleDateString()} · {formatBytes(resume.sizeBytes)}
        </p>
      </div>
      <Badge tone={scoreTone(resume.resumeScore)}>ATS {resume.resumeScore ?? '—'}</Badge>
      <QuickActions resume={resume} />
    </div>
  );
}
