import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Sparkles } from 'lucide-react';
import { storyIntel, type StoryRecommendation } from '@/lib/storyIntel';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { EmptyState } from '@/components/ui/empty-state';

export interface StoryRecommendationPanelProps {
  /** Pre-fill from the surrounding page (e.g. Interview prep for a specific application). */
  defaultCompanyName?: string;
  defaultTargetRole?: string;
}

/**
 * Phase 7.15 — "which of my STAR stories fits this interview" panel, meant to be embedded on the
 * existing Interview prep surface. Ships dark: POST /api/story/recommend returns null on 404/409
 * when story.recommendation.enabled=false, so this renders an empty state instead of erroring.
 */
export function StoryRecommendationPanel({ defaultCompanyName, defaultTargetRole }: StoryRecommendationPanelProps) {
  const [companyName, setCompanyName] = useState(defaultCompanyName ?? '');
  const [targetRole, setTargetRole] = useState(defaultTargetRole ?? '');
  const [question, setQuestion] = useState('');
  const [results, setResults] = useState<StoryRecommendation[] | null>(null);

  const recommend = useMutation({
    mutationFn: () => storyIntel.recommend({ companyName, targetRole, question }),
    onSuccess: (data) => setResults(data ?? []),
  });

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 text-base">
          <Sparkles className="h-4 w-4 text-primary" />
          Best STAR story for this interview
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
          <Input
            placeholder="Company"
            value={companyName}
            onChange={(e) => setCompanyName(e.target.value)}
          />
          <Input
            placeholder="Target role"
            value={targetRole}
            onChange={(e) => setTargetRole(e.target.value)}
          />
          <Input
            placeholder="Interview question"
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
          />
        </div>
        <Button size="sm" loading={recommend.isPending} onClick={() => recommend.mutate()}>
          Suggest story
        </Button>

        {results && results.length === 0 && (
          <EmptyState
            compact
            icon={Sparkles}
            title="No matching stories"
            description="Generate a STAR story first, or this feature may not be enabled yet."
          />
        )}
        {results && results.length > 0 && (
          <div className="space-y-2">
            {results.map((r) => (
              <div key={r.id} className="rounded-lg border border-border p-3 text-sm">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium text-foreground">{r.storyTitle ?? 'Untitled story'}</span>
                  <Badge tone="success">match {r.matchScore ?? 0}</Badge>
                </div>
                {r.reason && <p className="mt-1 text-xs text-muted-foreground">{r.reason}</p>}
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
