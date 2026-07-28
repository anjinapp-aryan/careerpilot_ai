import { useQuery } from '@tanstack/react-query';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip as RTooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { Globe2, Plane, Stamp, Users } from 'lucide-react';
import { api } from '@/lib/api';
import { PageHeader } from '@/components/common/PageHeader';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { EmptyState } from '@/components/ui/empty-state';
import { Skeleton } from '@/components/ui/skeleton';

interface CountryJobCount {
  countryCode: string;
  jobCount: number;
  avgMatchScore: number | null;
}
interface CountrySalary {
  countryCode: string;
  avgSalary: number | null;
  currency: string | null;
}
interface SkillFamilyCount {
  skillFamily: string;
  jobCount: number;
}
interface PipelineFunnel {
  discovered: number;
  eligible: number;
  recommended: number;
  applied: number;
}
interface DashboardData {
  topCountries: CountryJobCount[];
  avgSalaryByCountry: CountrySalary[];
  visaSponsoredCount: number;
  totalInternationalJobs: number;
  techHeatmap: SkillFamilyCount[];
  pipelineFunnel: PipelineFunnel;
}

function ChartTooltip({ active, payload, label }: any) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-lg border border-border bg-popover px-3 py-2 text-xs shadow-lg">
      <p className="mb-1 font-medium text-foreground">{label}</p>
      {payload.map((p: any) => (
        <p key={p.name} className="flex items-center gap-1.5 text-muted-foreground">
          <span className="inline-block h-2 w-2 rounded-full" style={{ background: p.color || p.fill }} />
          {p.name}: <span className="font-semibold text-foreground">{p.value}</span>
        </p>
      ))}
    </div>
  );
}

function StatTile({ icon: Icon, label, value }: { icon: typeof Globe2; label: string; value: number | string }) {
  return (
    <Card className="p-4">
      <div className="flex items-center gap-3">
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
          <Icon className="h-4.5 w-4.5" />
        </div>
        <div>
          <p className="text-xs text-muted-foreground">{label}</p>
          <p className="text-xl font-semibold tabular-nums text-foreground">{value}</p>
        </div>
      </div>
    </Card>
  );
}

/**
 * International Job Discovery Engine, Phase 1 — the first dedicated page for the new
 * international-relocation ranking data. Reuses the existing Recharts + CSS-variable-token
 * styling from Dashboard.tsx (no new charting library). Every section renders an EmptyState
 * instead of an error when `career.international.ranking.enabled` is off or the user has no
 * ranking rows yet — dark-safe throughout, same discipline as CompanyIntelligenceDashboard.
 */
export default function InternationalDashboard() {
  const { data, isLoading } = useQuery<DashboardData>({
    queryKey: ['jobs', 'international', 'dashboard'],
    queryFn: async () => (await api.get('/api/jobs/international/dashboard')).data,
    retry: false,
  });

  const hasData = !!data && data.totalInternationalJobs > 0;

  return (
    <div className="space-y-4">
      <PageHeader
        title="International Dashboard"
        description="Country-tier ranking, visa signals, and relocation viability for your international job matches."
      />

      {isLoading ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-20 rounded-xl" />)}
        </div>
      ) : !hasData ? (
        <EmptyState
          icon={Globe2}
          title="No international matches yet"
          description="Set your preferred relocation countries in Job preferences, then refresh recommendations to populate this dashboard."
        />
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <StatTile icon={Globe2} label="International jobs" value={data.totalInternationalJobs} />
            <StatTile icon={Stamp} label="Visa-sponsored jobs" value={data.visaSponsoredCount} />
            <StatTile
              icon={Users}
              label="Recommended"
              value={data.pipelineFunnel.recommended}
            />
            <StatTile icon={Plane} label="Applied" value={data.pipelineFunnel.applied} />
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <Card>
              <CardHeader>
                <CardTitle>Top countries</CardTitle>
                <p className="text-sm text-muted-foreground">Jobs by country, avg rank score</p>
              </CardHeader>
              <CardContent>
                {data.topCountries.length === 0 ? (
                  <EmptyState compact icon={Globe2} title="No country data yet" description="" />
                ) : (
                  <ResponsiveContainer width="100%" height={Math.max(180, data.topCountries.length * 40)}>
                    <BarChart data={data.topCountries} layout="vertical" margin={{ left: 8, right: 16, top: 8 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" horizontal={false} />
                      <XAxis type="number" allowDecimals={false} tickLine={false} axisLine={false} tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }} />
                      <YAxis dataKey="countryCode" type="category" tickLine={false} axisLine={false} width={48}
                        tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }}
                        tickFormatter={(v: string) => v.toUpperCase()} />
                      <RTooltip content={<ChartTooltip />} cursor={{ fill: 'hsl(var(--muted))' }} />
                      <Bar dataKey="jobCount" name="Jobs" radius={[0, 6, 6, 0]} maxBarSize={28} fill="hsl(var(--primary))" />
                    </BarChart>
                  </ResponsiveContainer>
                )}
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle>Average salary by country</CardTitle>
                <p className="text-sm text-muted-foreground">Midpoint of posted salary range</p>
              </CardHeader>
              <CardContent>
                {data.avgSalaryByCountry.length === 0 ? (
                  <EmptyState compact icon={Globe2} title="No salary data yet" description="" />
                ) : (
                  <ResponsiveContainer width="100%" height={Math.max(180, data.avgSalaryByCountry.length * 40)}>
                    <BarChart data={data.avgSalaryByCountry} layout="vertical" margin={{ left: 8, right: 16, top: 8 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" horizontal={false} />
                      <XAxis type="number" tickLine={false} axisLine={false} tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }} />
                      <YAxis dataKey="countryCode" type="category" tickLine={false} axisLine={false} width={48}
                        tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }}
                        tickFormatter={(v: string) => v.toUpperCase()} />
                      <RTooltip content={<ChartTooltip />} cursor={{ fill: 'hsl(var(--muted))' }} />
                      <Bar dataKey="avgSalary" name="Avg salary" radius={[0, 6, 6, 0]} maxBarSize={28} fill="hsl(var(--success))" />
                    </BarChart>
                  </ResponsiveContainer>
                )}
              </CardContent>
            </Card>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <Card>
              <CardHeader>
                <CardTitle>Top skill families</CardTitle>
                <p className="text-sm text-muted-foreground">Across your international matches</p>
              </CardHeader>
              <CardContent>
                {data.techHeatmap.length === 0 ? (
                  <EmptyState compact icon={Globe2} title="No skill data yet" description="" />
                ) : (
                  <ResponsiveContainer width="100%" height={Math.max(180, data.techHeatmap.length * 32)}>
                    <BarChart data={data.techHeatmap} layout="vertical" margin={{ left: 8, right: 16, top: 8 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" horizontal={false} />
                      <XAxis type="number" allowDecimals={false} tickLine={false} axisLine={false} tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }} />
                      <YAxis dataKey="skillFamily" type="category" tickLine={false} axisLine={false} width={96}
                        tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }} />
                      <RTooltip content={<ChartTooltip />} cursor={{ fill: 'hsl(var(--muted))' }} />
                      <Bar dataKey="jobCount" name="Jobs" radius={[0, 6, 6, 0]} maxBarSize={20} fill="hsl(var(--secondary))" />
                    </BarChart>
                  </ResponsiveContainer>
                )}
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle>Pipeline funnel</CardTitle>
                <p className="text-sm text-muted-foreground">Discovered → eligible → recommended → applied</p>
              </CardHeader>
              <CardContent>
                <ResponsiveContainer width="100%" height={220}>
                  <BarChart
                    data={[
                      { name: 'Discovered', value: data.pipelineFunnel.discovered, tone: 'hsl(var(--muted-foreground))' },
                      { name: 'Eligible', value: data.pipelineFunnel.eligible, tone: 'hsl(var(--secondary))' },
                      { name: 'Recommended', value: data.pipelineFunnel.recommended, tone: 'hsl(var(--primary))' },
                      { name: 'Applied', value: data.pipelineFunnel.applied, tone: 'hsl(var(--success))' },
                    ]}
                    margin={{ left: -20, right: 8, top: 8 }}
                  >
                    <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" vertical={false} />
                    <XAxis dataKey="name" tickLine={false} axisLine={false} tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} />
                    <YAxis allowDecimals={false} tickLine={false} axisLine={false} tick={{ fontSize: 12, fill: 'hsl(var(--muted-foreground))' }} width={32} />
                    <RTooltip content={<ChartTooltip />} cursor={{ fill: 'hsl(var(--muted))' }} />
                    <Bar dataKey="value" radius={[6, 6, 0, 0]} maxBarSize={56}>
                      {[
                        { name: 'Discovered', tone: 'hsl(var(--muted-foreground))' },
                        { name: 'Eligible', tone: 'hsl(var(--secondary))' },
                        { name: 'Recommended', tone: 'hsl(var(--primary))' },
                        { name: 'Applied', tone: 'hsl(var(--success))' },
                      ].map((p) => (
                        <Cell key={p.name} fill={p.tone} />
                      ))}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              </CardContent>
            </Card>
          </div>
        </>
      )}
    </div>
  );
}
