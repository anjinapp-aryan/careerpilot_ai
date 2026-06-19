import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { Check, X } from 'lucide-react';
import { useJobs } from '@/hooks/useJobs';
import { useClickOutside } from '@/hooks/useClickOutside';
import { SkeletonRows } from '@/components/common/Skeleton';
import { cn } from '@/lib/cn';
import type { Job } from '@/types/workflow';

interface JobMultiSelectProps {
  /** Selected job ids. Controlled. */
  value: string[];
  onChange: (jobIds: string[]) => void;
  id?: string;
  disabled?: boolean;
}

/**
 * Searchable multi-select for jobs. Selected jobs render as removable chips;
 * the popover lists matches with checkbox-style options. Fully keyboard
 * accessible with loading / error / empty states.
 */
export function JobMultiSelect({ value, onChange, id, disabled = false }: JobMultiSelectProps) {
  const { data: jobs, isLoading, isError, error, refetch } = useJobs();

  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);

  const rootRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const listboxId = useId();

  useClickOutside(rootRef, () => setOpen(false), open);

  const selectedSet = useMemo(() => new Set(value), [value]);
  const selectedJobs = useMemo<Job[]>(
    () => (jobs ?? []).filter((j) => selectedSet.has(j.id)),
    [jobs, selectedSet],
  );

  const filtered = useMemo<Job[]>(() => {
    const list = jobs ?? [];
    const q = query.trim().toLowerCase();
    if (!q) return list;
    return list.filter(
      (j) => j.title.toLowerCase().includes(q) || j.company.toLowerCase().includes(q),
    );
  }, [jobs, query]);

  useEffect(() => setActiveIndex(0), [query, open]);
  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);

  function toggle(job: Job) {
    if (selectedSet.has(job.id)) onChange(value.filter((id) => id !== job.id));
    else onChange([...value, job.id]);
  }

  function remove(jobId: string) {
    onChange(value.filter((id) => id !== jobId));
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
      if (open && opt) toggle(opt);
      else setOpen(true);
    } else if (e.key === 'Escape') {
      setOpen(false);
    } else if (e.key === 'Backspace' && query === '' && value.length > 0) {
      remove(value[value.length - 1]);
    } else if (e.key === 'Home' && open) {
      e.preventDefault();
      setActiveIndex(0);
    } else if (e.key === 'End' && open) {
      e.preventDefault();
      setActiveIndex(filtered.length - 1);
    }
  }

  return (
    <div ref={rootRef} className="relative">
      <div
        role="combobox"
        aria-expanded={open}
        aria-haspopup="listbox"
        aria-controls={listboxId}
        className="flex min-h-[2.75rem] w-full flex-wrap items-center gap-1.5 rounded-lg border border-input bg-card px-2 py-1.5 shadow-xs transition-colors focus-within:ring-2 focus-within:ring-ring hover:border-primary/40"
      >
        {selectedJobs.map((job) => (
          <span
            key={job.id}
            className="inline-flex items-center gap-1 rounded-md bg-primary/10 py-1 pl-2 pr-1 text-xs font-medium text-primary"
          >
            <span className="max-w-[12rem] truncate">
              {job.title}
              <span className="text-primary/60"> · {job.company}</span>
            </span>
            <button
              type="button"
              onClick={() => remove(job.id)}
              disabled={disabled}
              aria-label={`Remove ${job.title}`}
              className="rounded p-0.5 text-primary/80 hover:bg-primary/20 hover:text-primary focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <X className="h-3 w-3" aria-hidden="true" />
            </button>
          </span>
        ))}

        <input
          ref={inputRef}
          id={id}
          type="text"
          value={query}
          disabled={disabled || isLoading}
          onChange={(e) => setQuery(e.target.value)}
          onFocus={() => setOpen(true)}
          onKeyDown={onKeyDown}
          placeholder={
            selectedJobs.length === 0
              ? isLoading
                ? 'Loading jobs…'
                : 'Search and select jobs…'
              : 'Add another…'
          }
          aria-label="Search jobs"
          aria-controls={listboxId}
          aria-autocomplete="list"
          className="min-w-[8rem] flex-1 bg-transparent px-1.5 py-1 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none disabled:cursor-not-allowed"
        />
      </div>

      {open && (
        <div className="absolute z-20 mt-2 w-full overflow-hidden rounded-xl border border-border bg-popover shadow-lg">
          <ul
            id={listboxId}
            role="listbox"
            aria-label="Jobs"
            aria-multiselectable="true"
            className="max-h-64 overflow-auto p-1.5"
          >
            {isLoading && (
              <li className="p-2">
                <SkeletonRows rows={4} />
              </li>
            )}

            {isError && (
              <li className="px-3 py-4 text-sm text-danger" role="alert">
                <p>Couldn’t load jobs.</p>
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
                {(jobs?.length ?? 0) === 0
                  ? 'No jobs yet. Add one from the Jobs page.'
                  : 'No jobs match your search.'}
              </li>
            )}

            {!isLoading &&
              !isError &&
              filtered.map((job, index) => {
                const isSelected = selectedSet.has(job.id);
                const isActive = index === activeIndex;
                return (
                  <li
                    key={job.id}
                    role="option"
                    aria-selected={isSelected}
                    onMouseEnter={() => setActiveIndex(index)}
                    onClick={() => toggle(job)}
                    className={cn(
                      'flex cursor-pointer items-center gap-2.5 rounded-lg px-3 py-2 text-sm',
                      isActive && 'bg-muted',
                    )}
                  >
                    <span
                      className={cn(
                        'flex h-4 w-4 shrink-0 items-center justify-center rounded border',
                        isSelected
                          ? 'border-primary bg-primary text-primary-foreground'
                          : 'border-border bg-transparent',
                      )}
                      aria-hidden="true"
                    >
                      {isSelected && <Check className="h-3 w-3" />}
                    </span>
                    <span className="flex min-w-0 flex-col">
                      <span className="truncate text-foreground">{job.title}</span>
                      <span className="truncate text-xs text-muted-foreground">{job.company}</span>
                    </span>
                  </li>
                );
              })}
          </ul>

          {!isLoading && !isError && value.length > 0 && (
            <div className="flex items-center justify-between border-t border-border px-3 py-2 text-xs text-muted-foreground">
              <span>{value.length} selected</span>
              <button
                type="button"
                onClick={() => onChange([])}
                className="rounded px-2 py-1 font-medium text-foreground hover:bg-muted"
              >
                Clear all
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export default JobMultiSelect;
