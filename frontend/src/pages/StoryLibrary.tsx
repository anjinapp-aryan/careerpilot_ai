import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { BookOpen, Plus, RefreshCw } from 'lucide-react';
import { storyIntel, type StoryType } from '@/lib/storyIntel';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/ui/empty-state';
import { CompetencyWidget } from '@/components/story/CompetencyWidget';
import { StoryRecommendationPanel } from '@/components/story/StoryRecommendationPanel';

const QUALITY_TONE = (score?: number | null) => {
  if (score == null) return 'neutral' as const;
  if (score >= 75) return 'success' as const;
  if (score >= 50) return 'warning' as const;
  return 'danger' as const;
};

/**
 * Phase 7.15 — STAR Story Dashboard / Story Library. Ships dark: every list/query resolves to
 * null on 404 (story.engine.enabled=false), rendering the same empty state as a brand-new account.
 */
export default function StoryLibrary() {
  const queryClient = useQueryClient();
  const [hint, setHint] = useState('');
  const [storyType, setStoryType] = useState<StoryType | ''>('');

  const stories = useQuery({
    queryKey: ['story', 'list'],
    queryFn: () => storyIntel.list(),
    retry: false,
    staleTime: 30_000,
  });

  const categories = useQuery({
    queryKey: ['story', 'categories'],
    queryFn: () => storyIntel.categories(),
    retry: false,
    staleTime: 5 * 60_000,
  });

  const generate = useMutation({
    mutationFn: () => storyIntel.generate({ storyType: storyType || undefined, hint: hint || undefined }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['story'] });
      setHint('');
    },
  });

  if (stories.isLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  if (stories.data === null) {
    return (
      <EmptyState
        icon={BookOpen}
        title="STAR Story Intelligence is not enabled yet"
        description="Ask an admin to enable story.engine.enabled to start building your behavioral story library."
      />
    );
  }

  const list = stories.data ?? [];

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold text-foreground">STAR Story Library</h1>
          <p className="text-sm text-muted-foreground">
            Behavioral interview stories, generated from your resume and application history.
          </p>
        </div>
        <Button
          variant="ghost"
          size="sm"
          onClick={() => queryClient.invalidateQueries({ queryKey: ['story'] })}
        >
          <RefreshCw className="h-4 w-4" />
        </Button>
      </div>

      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">Generate a new story</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
            <select
              className="h-10 rounded-lg border border-border bg-card px-3 text-sm"
              value={storyType}
              onChange={(e) => setStoryType(e.target.value as StoryType | '')}
            >
              <option value="">Any story type</option>
              {(categories.data ?? []).map((c) => (
                <option key={c} value={c}>
                  {c.replace(/_/g, ' ').toLowerCase()}
                </option>
              ))}
            </select>
            <Input
              className="sm:col-span-2"
              placeholder="Optional guidance (e.g. focus on the incident I resolved last quarter)"
              value={hint}
              onChange={(e) => setHint(e.target.value)}
            />
          </div>
          <Button size="sm" loading={generate.isPending} onClick={() => generate.mutate()}>
            <Plus className="h-4 w-4" />
            Generate STAR story
          </Button>
        </CardContent>
      </Card>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <CompetencyWidget />
        <StoryRecommendationPanel />
      </div>

      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">Your stories ({list.length})</CardTitle>
        </CardHeader>
        <CardContent>
          {list.length === 0 ? (
            <EmptyState
              icon={BookOpen}
              title="No STAR stories yet"
              description="Generate your first story above, or it will build automatically as you apply and interview."
            />
          ) : (
            <div className="divide-y divide-border">
              {list.map((s) => (
                <div key={s.id} className="flex items-center justify-between gap-3 py-3">
                  <div>
                    <div className="text-sm font-medium text-foreground">{s.title}</div>
                    <div className="text-xs text-muted-foreground">
                      {s.storyType.replace(/_/g, ' ').toLowerCase()} · {s.status.toLowerCase()}
                    </div>
                  </div>
                  <Badge tone={QUALITY_TONE(s.qualityScore)}>
                    quality {s.qualityScore ?? 'n/a'}
                  </Badge>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
