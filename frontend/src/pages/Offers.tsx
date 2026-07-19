import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Banknote, Plus, Scale, Sparkles } from 'lucide-react';
import { api } from '@/lib/api';
import { PageHeader } from '@/components/common/PageHeader';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { useToast } from '@/components/ui/toast';
import type { ManualOfferRequest, Offer, OfferComparisonResult } from '@/types/workflow';

function money(v?: number | null, currency?: string | null): string {
  if (v == null) return '—';
  const c = currency || 'USD';
  try {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: c, maximumFractionDigits: 0 }).format(v);
  } catch {
    return `${c} ${v.toLocaleString()}`;
  }
}

const EMPTY_FORM: ManualOfferRequest = {
  companyName: '',
  baseSalary: undefined,
  bonus: undefined,
  rsuValue: undefined,
  joiningBonus: undefined,
  currency: 'USD',
  equityDescription: '',
  benefitsSummary: '',
};

/**
 * Gap B — Offer Intelligence & Salary Negotiation. Reads offers captured by the LangGraph
 * salary_intelligence agent (via OfferAnalysisService) alongside manually-entered offers, and
 * renders a deterministic side-by-side comparison. Ships dark behind
 * `offer.intelligence.enabled` — GET /api/offers returns an empty list while off, so this page
 * renders its empty state rather than erroring.
 *
 * This page is purely informational: comparisons show numeric deltas only. It never presents
 * legal or financial advice (see OfferIntelligenceHandler's system prompt for the Copilot-side
 * enforcement of the same rule).
 */
export default function Offers() {
  const qc = useQueryClient();
  const { toast } = useToast();
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState<ManualOfferRequest>(EMPTY_FORM);

  const offersQuery = useQuery<Offer[]>({
    queryKey: ['offers'],
    queryFn: async () => {
      try {
        return (await api.get('/api/offers')).data;
      } catch {
        return [];
      }
    },
    retry: false,
  });

  const offers = offersQuery.data ?? [];

  const selectedIds = useMemo(() => Array.from(selected), [selected]);

  const comparisonQuery = useQuery<OfferComparisonResult | null>({
    queryKey: ['offers', 'compare', selectedIds],
    queryFn: async () => {
      if (selectedIds.length < 2) return null;
      try {
        return (await api.get('/api/offers/compare', { params: { ids: selectedIds } })).data;
      } catch {
        return null;
      }
    },
    enabled: selectedIds.length >= 2,
  });

  const create = useMutation({
    mutationFn: async (payload: ManualOfferRequest) => (await api.post('/api/offers', payload)).data,
    onSuccess: () => {
      toast({ variant: 'success', title: 'Offer added' });
      setForm(EMPTY_FORM);
      setShowForm(false);
      qc.invalidateQueries({ queryKey: ['offers'] });
    },
    onError: () => toast({ variant: 'error', title: 'Could not add offer (feature may be disabled)' }),
  });

  function toggleSelect(id: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  const isEmpty = !offersQuery.isLoading && offers.length === 0;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Offers"
        description="Offer intelligence and salary negotiation — captured automatically from the AI workflow's Salary Intelligence agent, plus offers you add manually. Comparisons are factual numeric deltas only, never legal or financial advice."
        actions={
          <Button size="sm" variant="outline" onClick={() => setShowForm((s) => !s)}>
            <Plus className="h-4 w-4" />
            Add offer
          </Button>
        }
      />

      {showForm && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">New offer</CardTitle>
          </CardHeader>
          <CardContent>
            <form
              className="grid gap-3 sm:grid-cols-2"
              onSubmit={(e) => {
                e.preventDefault();
                create.mutate(form);
              }}
            >
              <Input
                placeholder="Company name"
                value={form.companyName ?? ''}
                onChange={(e) => setForm((f) => ({ ...f, companyName: e.target.value }))}
              />
              <Input
                placeholder="Currency (e.g. USD)"
                value={form.currency ?? ''}
                onChange={(e) => setForm((f) => ({ ...f, currency: e.target.value }))}
              />
              <Input
                type="number"
                placeholder="Base salary"
                value={form.baseSalary ?? ''}
                onChange={(e) => setForm((f) => ({ ...f, baseSalary: e.target.value ? Number(e.target.value) : undefined }))}
              />
              <Input
                type="number"
                placeholder="Bonus"
                value={form.bonus ?? ''}
                onChange={(e) => setForm((f) => ({ ...f, bonus: e.target.value ? Number(e.target.value) : undefined }))}
              />
              <Input
                type="number"
                placeholder="RSU / equity value"
                value={form.rsuValue ?? ''}
                onChange={(e) => setForm((f) => ({ ...f, rsuValue: e.target.value ? Number(e.target.value) : undefined }))}
              />
              <Input
                type="number"
                placeholder="Joining bonus"
                value={form.joiningBonus ?? ''}
                onChange={(e) => setForm((f) => ({ ...f, joiningBonus: e.target.value ? Number(e.target.value) : undefined }))}
              />
              <Input
                placeholder="Equity description (optional)"
                className="sm:col-span-2"
                value={form.equityDescription ?? ''}
                onChange={(e) => setForm((f) => ({ ...f, equityDescription: e.target.value }))}
              />
              <Input
                placeholder="Benefits summary (optional)"
                className="sm:col-span-2"
                value={form.benefitsSummary ?? ''}
                onChange={(e) => setForm((f) => ({ ...f, benefitsSummary: e.target.value }))}
              />
              <div className="sm:col-span-2 flex justify-end gap-2">
                <Button type="button" variant="ghost" size="sm" onClick={() => setShowForm(false)}>
                  Cancel
                </Button>
                <Button type="submit" size="sm" loading={create.isPending}>
                  Save offer
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>
      )}

      {isEmpty ? (
        <EmptyState
          icon={Banknote}
          title="No offers yet"
          description="Offers captured by the AI workflow's Salary Intelligence agent will show up here automatically once offer.intelligence.enabled is on, or add one manually above."
        />
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {offersQuery.isLoading
              ? Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-48 w-full" />)
              : offers.map((o) => (
                  <Card
                    key={o.id}
                    className={selected.has(o.id) ? 'ring-2 ring-primary' : undefined}
                    onClick={() => toggleSelect(o.id)}
                    role="button"
                  >
                    <CardHeader className="flex-row items-center justify-between pb-2">
                      <CardTitle className="text-sm">{o.companyName || 'Untitled offer'}</CardTitle>
                      {o.source === 'SALARY_INTELLIGENCE_AGENT' ? (
                        <Badge tone="primary">
                          <Sparkles className="h-3 w-3" /> AI
                        </Badge>
                      ) : (
                        <Badge tone="neutral">Manual</Badge>
                      )}
                    </CardHeader>
                    <CardContent className="space-y-1.5 text-sm">
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">Base</span>
                        <span className="tabular-nums">{money(o.baseSalary, o.currency)}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">Bonus</span>
                        <span className="tabular-nums">{money(o.bonus, o.currency)}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">RSU/equity</span>
                        <span className="tabular-nums">{money(o.rsuValue, o.currency)}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="text-muted-foreground">Joining bonus</span>
                        <span className="tabular-nums">{money(o.joiningBonus, o.currency)}</span>
                      </div>
                      {(o.marketP50 != null) && (
                        <p className="pt-1 text-xs text-muted-foreground">
                          Market p50: {money(o.marketP50, o.currency)}
                        </p>
                      )}
                    </CardContent>
                  </Card>
                ))}
          </div>

          <Card>
            <CardHeader className="flex-row items-center gap-2">
              <Scale className="h-4 w-4 text-muted-foreground" />
              <CardTitle className="text-base">Compare selected offers</CardTitle>
            </CardHeader>
            <CardContent>
              {selectedIds.length < 2 ? (
                <p className="text-sm text-muted-foreground">Select two or more offer cards above to compare.</p>
              ) : comparisonQuery.isLoading ? (
                <Skeleton className="h-32 w-full" />
              ) : !comparisonQuery.data || comparisonQuery.data.rows.length === 0 ? (
                <p className="text-sm text-muted-foreground">Not enough data to compare.</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted-foreground">
                        <th className="py-2 pr-4">Company</th>
                        <th className="py-2 pr-4">Base</th>
                        <th className="py-2 pr-4">Bonus</th>
                        <th className="py-2 pr-4">RSU</th>
                        <th className="py-2 pr-4">Joining bonus</th>
                        <th className="py-2 pr-4">Total comp</th>
                        <th className="py-2 pr-4">Δ from highest</th>
                      </tr>
                    </thead>
                    <tbody>
                      {comparisonQuery.data.rows.map((r) => (
                        <tr key={r.offerId} className="border-b border-border/50">
                          <td className="py-2 pr-4 font-medium">
                            {r.companyName || '—'}
                            {r.offerId === comparisonQuery.data?.highestOfferId && (
                              <Badge tone="success" className="ml-2">Highest</Badge>
                            )}
                          </td>
                          <td className="py-2 pr-4 tabular-nums">{money(r.components.baseSalary, r.currency)}</td>
                          <td className="py-2 pr-4 tabular-nums">{money(r.components.bonus, r.currency)}</td>
                          <td className="py-2 pr-4 tabular-nums">{money(r.components.rsuValue, r.currency)}</td>
                          <td className="py-2 pr-4 tabular-nums">{money(r.components.joiningBonus, r.currency)}</td>
                          <td className="py-2 pr-4 tabular-nums font-semibold">{money(r.components.totalComp, r.currency)}</td>
                          <td className="py-2 pr-4 tabular-nums text-muted-foreground">
                            {r.deltaFromHighest ? `-${money(r.deltaFromHighest, r.currency)}` : '—'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}
