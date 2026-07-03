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
  homeCountry: null,
  preferredCountries: [],
  preferredCities: [],
  preferredRoles: [],
  excludedRoles: [],
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

  // Raw text for the four comma-separated fields, kept separate from `form`'s parsed arrays so a
  // trailing "," while typing isn't immediately stripped by a parse-then-rejoin round trip.
  const [countriesText, setCountriesText] = useState('');
  const [citiesText, setCitiesText] = useState('');
  const [rolesText, setRolesText] = useState('');
  const [excludedText, setExcludedText] = useState('');

  const { data, isLoading } = useQuery<CandidatePreferences>({
    queryKey: ['candidate', 'preferences'],
    queryFn: async () => (await api.get('/api/candidate/preferences')).data,
    enabled: open,
  });

  const csv = (xs: string[]) => xs.join(', ');
  const parseCsv = (v: string) => v.split(',').map((s) => s.trim()).filter(Boolean);

  useEffect(() => {
    if (data) {
      const merged = { ...EMPTY, ...data };
      setForm(merged);
      setCountriesText(csv(merged.preferredCountries));
      setCitiesText(csv(merged.preferredCities));
      setRolesText(csv(merged.preferredRoles));
      setExcludedText(csv(merged.excludedRoles));
    }
  }, [data]);

  const save = useMutation({
    mutationFn: async () =>
      (
        await api.put('/api/candidate/preferences', {
          ...form,
          preferredCountries: parseCsv(countriesText),
          preferredCities: parseCsv(citiesText),
          preferredRoles: parseCsv(rolesText),
          excludedRoles: parseCsv(excludedText),
        })
      ).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['candidate', 'preferences'] });
      qc.invalidateQueries({ queryKey: ['candidate', 'profile'] });
      qc.invalidateQueries({ queryKey: ['jobs', 'recommended'] });
      toast({ variant: 'success', title: 'Preferences saved', description: 'Recommendations will update on next refresh.' });
      onOpenChange(false);
    },
    onError: () => toast({ variant: 'error', title: 'Could not save preferences' }),
  });

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
            <div>
              <Label>Home country</Label>
              <Input
                value={form.homeCountry ?? ''}
                onChange={(e) => setForm({ ...form, homeCountry: e.target.value || null })}
                placeholder="India"
              />
              <p className="mt-1 text-xs text-muted-foreground">
                Drives your <span className="font-medium">Domestic</span> jobs tab. International shows your preferred countries below.
              </p>
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              <div>
                <Label>Preferred countries</Label>
                <Input
                  value={countriesText}
                  onChange={(e) => setCountriesText(e.target.value)}
                  placeholder="India, Germany, United States"
                />
              </div>
              <div>
                <Label>Preferred cities</Label>
                <Input
                  value={citiesText}
                  onChange={(e) => setCitiesText(e.target.value)}
                  placeholder="Bangalore, Berlin"
                />
              </div>
            </div>

            <div>
              <Label>Preferred roles</Label>
              <Input
                value={rolesText}
                onChange={(e) => setRolesText(e.target.value)}
                placeholder="Java Architect, Backend Engineer"
              />
            </div>

            <div>
              <Label>Excluded roles</Label>
              <Input
                value={excludedText}
                onChange={(e) => setExcludedText(e.target.value)}
                placeholder="Sales, Marketing, Support"
              />
              <p className="mt-1 text-xs text-muted-foreground">
                Roles you never want to see — these are filtered out of recommendations.
              </p>
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
