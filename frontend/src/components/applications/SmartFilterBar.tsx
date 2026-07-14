import { Search, Star } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import type { ApplicationHealthStatus, ApplicationPriority } from '@/types/workflow';

export interface SmartFilters {
  query: string;
  remoteType: '' | 'REMOTE' | 'HYBRID' | 'ONSITE';
  priority: '' | ApplicationPriority;
  health: '' | ApplicationHealthStatus;
  favoritesOnly: boolean;
}

export const DEFAULT_FILTERS: SmartFilters = {
  query: '',
  remoteType: '',
  priority: '',
  health: '',
  favoritesOnly: false,
};

const REMOTE_OPTIONS: SmartFilters['remoteType'][] = ['', 'REMOTE', 'HYBRID', 'ONSITE'];
const PRIORITY_OPTIONS: SmartFilters['priority'][] = ['', 'HIGH', 'MEDIUM', 'LOW'];
const HEALTH_OPTIONS: SmartFilters['health'][] = ['', 'EXCELLENT', 'HEALTHY', 'NEEDS_ATTENTION', 'RISK', 'COLD', 'STALE'];

/**
 * Generalizes `Jobs.tsx`'s inline filter pattern into a reusable component for the Applications
 * command center. No recruiter/referral filters (omitted — no data exists for either anywhere).
 */
export function SmartFilterBar({ value, onChange }: { value: SmartFilters; onChange: (v: SmartFilters) => void }) {
  function set<K extends keyof SmartFilters>(key: K, v: SmartFilters[K]) {
    onChange({ ...value, [key]: v });
  }

  return (
    <div className="flex flex-wrap items-center gap-2 rounded-xl border border-border bg-card p-3">
      <div className="relative min-w-[12rem] flex-1">
        <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          placeholder="Search company or role…"
          className="pl-8"
          value={value.query}
          onChange={(e) => set('query', e.target.value)}
        />
      </div>

      <Select label="Remote" value={value.remoteType} options={REMOTE_OPTIONS} onChange={(v) => set('remoteType', v)} />
      <Select label="Priority" value={value.priority} options={PRIORITY_OPTIONS} onChange={(v) => set('priority', v)} />
      <Select label="Health" value={value.health} options={HEALTH_OPTIONS} onChange={(v) => set('health', v)} />

      <Button
        type="button"
        variant={value.favoritesOnly ? 'primary' : 'outline'}
        size="sm"
        onClick={() => set('favoritesOnly', !value.favoritesOnly)}
      >
        <Star className={cn('h-3.5 w-3.5', value.favoritesOnly && 'fill-current')} /> Favorites
      </Button>

      {(value.query || value.remoteType || value.priority || value.health || value.favoritesOnly) && (
        <Button type="button" variant="ghost" size="sm" onClick={() => onChange(DEFAULT_FILTERS)}>
          Clear
        </Button>
      )}
    </div>
  );
}

function Select<T extends string>({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: T;
  options: T[];
  onChange: (v: T) => void;
}) {
  return (
    <select
      aria-label={label}
      value={value}
      onChange={(e) => onChange(e.target.value as T)}
      className="h-9 rounded-lg border border-border bg-card px-2.5 text-xs font-medium text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
    >
      {options.map((opt) => (
        <option key={opt} value={opt}>
          {opt === '' ? label : opt.replaceAll('_', ' ')}
        </option>
      ))}
    </select>
  );
}
