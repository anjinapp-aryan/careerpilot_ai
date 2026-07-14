import { useQuery } from '@tanstack/react-query';
import { BrainCircuit } from 'lucide-react';
import { storyIntel } from '@/lib/storyIntel';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';

/** Phase 7.15 — small behavioral competency distribution widget, driven by GET /api/story/analytics. */
export function CompetencyWidget() {
  const { data, isLoading } = useQuery({
    queryKey: ['story', 'analytics'],
    queryFn: () => storyIntel.analytics(),
    retry: false,
    staleTime: 60_000,
  });

  if (isLoading) return <Skeleton className="h-40 w-full" />;
  if (!data) return null; // dark flag — render nothing

  let byCompetency: Record<string, number> = {};
  try {
    byCompetency = data.byCompetency ? JSON.parse(data.byCompetency) : {};
  } catch {
    byCompetency = {};
  }
  const entries = Object.entries(byCompetency).sort((a, b) => b[1] - a[1]).slice(0, 8);

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 text-base">
          <BrainCircuit className="h-4 w-4 text-primary" />
          Behavioral competencies
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {entries.length === 0 ? (
          <EmptyState
            compact
            icon={BrainCircuit}
            title="No stories scored yet"
            description="Generate or add a STAR story to see your competency coverage."
          />
        ) : (
          <div className="flex flex-wrap gap-1.5">
            {entries.map(([competency, count]) => (
              <Badge key={competency} tone="info">
                {competency.replace(/_/g, ' ').toLowerCase()} · {count}
              </Badge>
            ))}
          </div>
        )}
        <div className="text-xs text-muted-foreground">
          {data.totalStories ?? 0} stories · avg quality {data.avgQualityScore ?? 'n/a'}
        </div>
      </CardContent>
    </Card>
  );
}
