import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Building2, Network } from 'lucide-react';
import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip as RTooltip, XAxis, YAxis } from 'recharts';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { companyIntel } from '@/lib/companyIntel';

const CHART_COLORS = ['hsl(var(--primary))', 'hsl(var(--secondary))', 'hsl(var(--success))', 'hsl(var(--warning))', 'hsl(var(--danger))'];

function tone(v?: number | null): BadgeTone {
  if (v == null) return 'neutral';
  if (v >= 70) return 'success';
  if (v >= 45) return 'warning';
  return 'danger';
}

function toChart(raw?: Record<string, number>): { name: string; value: number }[] {
  return Object.entries(raw ?? {})
    .map(([name, value]) => ({ name, value }))
    .sort((a, b) => b.value - a.value)
    .slice(0, 8);
}

function MiniDistribution({ title, data }: { title: string; data: { name: string; value: number }[] }) {
  if (data.length === 0) return null;
  return (
    <div>
      <p className="mb-1 text-xs font-semibold text-foreground">{title}</p>
      <ResponsiveContainer width="100%" height={Math.max(120, data.length * 22)}>
        <BarChart data={data} layout="vertical" margin={{ left: 8, right: 16, top: 4, bottom: 4 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" horizontal={false} />
          <XAxis type="number" allowDecimals={false} tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} />
          <YAxis type="category" dataKey="name" width={100} tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} />
          <RTooltip cursor={{ fill: 'hsl(var(--muted))' }} />
          <Bar dataKey="value" radius={[0, 6, 6, 0]} maxBarSize={14}>
            {data.map((_, i) => (
              <Cell key={i} fill={CHART_COLORS[i % CHART_COLORS.length]} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

/**
 * Phase 7.13 — Company Intelligence dashboard widget: top companies by quality plus technology and
 * industry distribution from the knowledge graph. Dark-safe: /api/company/statistics 404s while
 * company.knowledge.enabled / company.analytics.enabled are off → renders nothing.
 */
export function CompanyIntelligenceWidget() {
  const stats = useQuery({
    queryKey: ['company-intel', 'statistics'],
    queryFn: companyIntel.statistics,
    staleTime: 5 * 60_000,
    retry: false,
  });

  const data = stats.data;
  if (!data || data.companies === 0) return null;

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between pb-2">
        <CardTitle className="flex items-center gap-2 text-base">
          <Network className="h-4 w-4 text-primary" />
          Company intelligence
          <Badge tone="neutral">{data.companies} companies</Badge>
          <Badge tone="neutral">{data.graphEdges} signals</Badge>
        </CardTitle>
        <Link to="/companies" className="text-sm font-medium text-primary hover:underline">
          View all
        </Link>
      </CardHeader>
      <CardContent className="space-y-4">
        {data.topCompanies.length > 0 && (
          <div>
            <p className="mb-1 flex items-center gap-1 text-xs font-semibold text-foreground">
              <Building2 className="h-3 w-3" /> Top companies by quality
            </p>
            <ul className="space-y-1">
              {data.topCompanies.slice(0, 6).map((company) => (
                <li key={company.id} className="flex items-center justify-between text-xs">
                  <span className="text-foreground">{company.companyName}</span>
                  <span className="flex gap-1">
                    <Badge tone={tone(company.qualityScore)}>q {company.qualityScore ?? '—'}</Badge>
                    <Badge tone={tone(company.hiringProbability)}>hire {company.hiringProbability ?? '—'}</Badge>
                  </span>
                </li>
              ))}
            </ul>
          </div>
        )}
        <div className="grid gap-4 sm:grid-cols-2">
          <MiniDistribution title="Technology distribution" data={toChart(data.technologyDistribution)} />
          <MiniDistribution title="Industry distribution" data={toChart(data.industryDistribution)} />
        </div>
      </CardContent>
    </Card>
  );
}
