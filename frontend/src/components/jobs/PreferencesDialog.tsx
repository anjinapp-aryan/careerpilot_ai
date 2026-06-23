import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
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
import type { CandidatePreferences } from '@/types/workflow';

interface PreferencesDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const EMPTY: CandidatePreferences = {
  preferredCountries: [],
  preferredCities: [],
  preferredRoles: [],
  remotePreference: false,
  hybridPreference: false,
  onsitePreference: false,
  visaSponsorshipRequired: false,
  relocationRequired: false,
  salaryExpectationMin: null,
  salaryExpectationMax: null,
  salaryCurrency: null,
};

function Toggle({ checked, onChange, label }: { checked: boolean; onChange: () => void; label: string }) {
  return (
    <label className="flex cursor-pointer items-center justify-between rounded-lg border border-border bg-card px-3 py-2.5">
      <span className="text-sm font-medium text-foreground">{label}</span>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        onClick={onChange}
        className={cn('relative h-5 w-9 rounded-full transition-colors', checked ? 'bg-primary' : 'bg-muted')}
      >
        <span
          className={cn(
            'absolute top-0.5 h-4 w-4 rounded-full bg-white shadow transition-transform',
            checked ? 'translate-x-4' : 'translate-x-0.5',
          )}
        />
      </button>
    </label>
  );
}

export function PreferencesDialog({ open, onOpenChange }: PreferencesDialogProps) {
  const qc = useQueryClient();
  const { toast } = useToast();
  const [form, setForm] = useState<CandidatePreferences>(EMPTY);

  const { data, isLoading } = useQuery<CandidatePreferences>({
    queryKey: ['candidate', 'preferences'],
    queryFn: async () => (await api.get('/api/candidate/preferences')).data,
    enabled: open,
  });

  useEffect(() => {
    if (data) setForm({ ...EMPTY, ...data });
  }, [data]);

  const save = useMutation({
    mutationFn: async () => (await api.put('/api/candidate/preferences', form)).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['candidate', 'preferences'] });
      qc.invalidateQueries({ queryKey: ['jobs', 'recommended'] });
      toast({ variant: 'success', title: 'Preferences saved', description: 'Recommendations will update on next refresh.' });
      onOpenChange(false);
    },
    onError: () => toast({ variant: 'error', title: 'Could not save preferences' }),
  });

  const csv = (xs: string[]) => xs.join(', ');
  const parseCsv = (v: string) => v.split(',').map((s) => s.trim()).filter(Boolean);
  const numOrNull = (v: string) => (v.trim() === '' ? null : Number(v));

  return (
    <Dialog open={open} onOpenChange={onOpenChange} size="lg">
      <DialogHeader onClose={() => onOpenChange(false)}>
        <DialogTitle>Job preferences</DialogTitle>
        <DialogDescription>Tune how we score and recommend roles for you.</DialogDescription>
      </DialogHeader>
      <DialogBody className="space-y-5">
        {isLoading ? (
          <div className="space-y-3">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} className="h-11 rounded-lg" />
            ))}
          </div>
        ) : (
          <>
            <div className="grid gap-4 sm:grid-cols-2">
              <div>
                <Label>Preferred countries</Label>
                <Input
                  value={csv(form.preferredCountries)}
                  onChange={(e) => setForm({ ...form, preferredCountries: parseCsv(e.target.value) })}
                  placeholder="India, Germany, United States"
                />
              </div>
              <div>
                <Label>Preferred cities</Label>
                <Input
                  value={csv(form.preferredCities)}
                  onChange={(e) => setForm({ ...form, preferredCities: parseCsv(e.target.value) })}
                  placeholder="Bangalore, Berlin"
                />
              </div>
            </div>

            <div>
              <Label>Preferred roles</Label>
              <Input
                value={csv(form.preferredRoles)}
                onChange={(e) => setForm({ ...form, preferredRoles: parseCsv(e.target.value) })}
                placeholder="Java Architect, Backend Engineer"
              />
            </div>

            <div className="grid gap-2 sm:grid-cols-3">
              <Toggle label="Open to Remote" checked={form.remotePreference} onChange={() => setForm({ ...form, remotePreference: !form.remotePreference })} />
              <Toggle label="Open to Hybrid" checked={form.hybridPreference} onChange={() => setForm({ ...form, hybridPreference: !form.hybridPreference })} />
              <Toggle label="Open to Onsite" checked={form.onsitePreference} onChange={() => setForm({ ...form, onsitePreference: !form.onsitePreference })} />
            </div>

            <div className="grid gap-2 sm:grid-cols-2">
              <Toggle label="Need visa sponsorship" checked={form.visaSponsorshipRequired} onChange={() => setForm({ ...form, visaSponsorshipRequired: !form.visaSponsorshipRequired })} />
              <Toggle label="Need relocation support" checked={form.relocationRequired} onChange={() => setForm({ ...form, relocationRequired: !form.relocationRequired })} />
            </div>

            <div className="grid gap-4 sm:grid-cols-3">
              <div>
                <Label>Salary min</Label>
                <Input
                  type="number"
                  value={form.salaryExpectationMin ?? ''}
                  onChange={(e) => setForm({ ...form, salaryExpectationMin: numOrNull(e.target.value) })}
                  placeholder="80000"
                />
              </div>
              <div>
                <Label>Salary max</Label>
                <Input
                  type="number"
                  value={form.salaryExpectationMax ?? ''}
                  onChange={(e) => setForm({ ...form, salaryExpectationMax: numOrNull(e.target.value) })}
                  placeholder="140000"
                />
              </div>
              <div>
                <Label>Currency</Label>
                <Input
                  value={form.salaryCurrency ?? ''}
                  onChange={(e) => setForm({ ...form, salaryCurrency: e.target.value || null })}
                  placeholder="USD"
                />
              </div>
            </div>
          </>
        )}
      </DialogBody>
      <DialogFooter>
        <Button variant="ghost" onClick={() => onOpenChange(false)}>
          Cancel
        </Button>
        <Button onClick={() => save.mutate()} loading={save.isPending} disabled={isLoading}>
          Save preferences
        </Button>
      </DialogFooter>
    </Dialog>
  );
}
