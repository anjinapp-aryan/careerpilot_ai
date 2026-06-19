import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { Check, ChevronDown } from 'lucide-react';
import { useResumes } from '@/hooks/useResumes';
import { useClickOutside } from '@/hooks/useClickOutside';
import { SkeletonRows } from '@/components/common/Skeleton';
import { cn } from '@/lib/cn';
import type { Resume } from '@/types/workflow';

interface ResumeSelectProps {
  /** Selected resume id, or '' when none chosen. Controlled. */
  value: string;
  onChange: (resumeId: string) => void;
  /** Associates the trigger with an external <label htmlFor>. */
  id?: string;
  disabled?: boolean;
}

function formatDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

/**
 * Searchable single-select dropdown for choosing a resume. Accessible combobox
 * (ARIA 1.2) with full keyboard support and loading / error / empty states.
 */
export function ResumeSelect({ value, onChange, id, disabled = false }: ResumeSelectProps) {
  const { data: resumes, isLoading, isError, error, refetch } = useResumes();

  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);

  const rootRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const listboxId = useId();

  useClickOutside(rootRef, () => setOpen(false), open);

  const selected = useMemo<Resume | undefined>(
    () => resumes?.find((r) => r.id === value),
    [resumes, value],
  );

  const filtered = useMemo<Resume[]>(() => {
    const list = resumes ?? [];
    const q = query.trim().toLowerCase();
    if (!q) return list;
    return list.filter((r) => r.filename.toLowerCase().includes(q));
  }, [resumes, query]);

  useEffect(() => setActiveIndex(0), [query, open]);
  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);

  function commit(resume: Resume) {
    onChange(resume.id);
    setOpen(false);
    setQuery('');
  }

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (!open) return setOpen(true);
      setActiveIndex((i) => Math.min(i + 1, filtered.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActiveIndex((i) => Math.max(i - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const opt = filtered[activeIndex];
      if (open && opt) commit(opt);
      else setOpen(true);
    } else if (e.key === 'Escape') {
      setOpen(false);
    } else if (e.key === 'Home' && open) {
      e.preventDefault();
      setActiveIndex(0);
    } else if (e.key === 'End' && open) {
      e.preventDefault();
      setActiveIndex(filtered.length - 1);
    }
  }

  const triggerLabel = selected
    ? selected.filename
    : isLoading
      ? 'Loading resumes…'
      : 'Select a resume';

  return (
    <div ref={rootRef} className="relative">
      <button
        type="button"
        id={id}
        disabled={disabled || isLoading}
        onClick={() => setOpen((o) => !o)}
        onKeyDown={onKeyDown}
        role="combobox"
        aria-expanded={open}
        aria-haspopup="listbox"
        aria-controls={listboxId}
        className="flex w-full items-center justify-between gap-2 rounded-lg border border-input bg-card px-3 py-2.5 text-left text-sm text-foreground shadow-xs transition-colors hover:border-primary/40 focus:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-60"
      >
        <span className="flex min-w-0 flex-col">
          <span className={cn('truncate', !selected && 'text-muted-foreground')}>
            {triggerLabel}
          </span>
          {selected && (
            <span className="truncate text-xs text-muted-foreground">
              Uploaded {formatDate(selected.createdAt)}
            </span>
          )}
        </span>
        <ChevronDown
          className={cn('h-4 w-4 shrink-0 text-muted-foreground transition-transform', open && 'rotate-180')}
          aria-hidden="true"
        />
      </button>

      {open && (
        <div className="absolute z-20 mt-2 w-full overflow-hidden rounded-xl border border-border bg-popover shadow-lg">
          <div className="border-b border-border p-2">
            <input
              ref={inputRef}
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={onKeyDown}
              placeholder="Search resumes…"
              aria-label="Search resumes"
              aria-controls={listboxId}
              aria-autocomplete="list"
              className="w-full rounded-md border border-input bg-card px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            />
          </div>

          <ul id={listboxId} role="listbox" aria-label="Resumes" className="max-h-64 overflow-auto p-1.5">
            {isLoading && (
              <li className="p-2">
                <SkeletonRows rows={3} />
              </li>
            )}

            {isError && (
              <li className="px-3 py-4 text-sm text-danger" role="alert">
                <p>Couldn’t load resumes.</p>
                <p className="mt-0.5 text-xs text-danger/80">
                  {error instanceof Error ? error.message : 'Please try again.'}
                </p>
                <button
                  type="button"
                  onClick={() => refetch()}
                  className="mt-2 rounded-md bg-muted px-2.5 py-1 text-xs font-medium text-foreground hover:bg-muted/70"
                >
                  Retry
                </button>
              </li>
            )}

            {!isLoading && !isError && filtered.length === 0 && (
              <li className="px-3 py-6 text-center text-sm text-muted-foreground">
                {(resumes?.length ?? 0) === 0
                  ? 'No resumes yet. Upload one from the Resumes page.'
                  : 'No resumes match your search.'}
              </li>
            )}

            {!isLoading &&
              !isError &&
              filtered.map((resume, index) => {
                const isSelected = resume.id === value;
                const isActive = index === activeIndex;
                return (
                  <li
                    key={resume.id}
                    role="option"
                    aria-selected={isSelected}
                    onMouseEnter={() => setActiveIndex(index)}
                    onClick={() => commit(resume)}
                    className={cn(
                      'flex cursor-pointer items-center justify-between gap-2 rounded-lg px-3 py-2 text-sm',
                      isActive && 'bg-muted',
                    )}
                  >
                    <span className="flex min-w-0 flex-col">
                      <span className="truncate text-foreground">{resume.filename}</span>
                      <span className="truncate text-xs text-muted-foreground">
                        {formatDate(resume.createdAt)}
                      </span>
                    </span>
                    {isSelected && <Check className="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />}
                  </li>
                );
              })}
          </ul>
        </div>
      )}
    </div>
  );
}

export default ResumeSelect;
