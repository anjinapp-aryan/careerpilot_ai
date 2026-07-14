import { GraduationCap } from 'lucide-react';
import { CompetencyWidget } from '@/components/story/CompetencyWidget';
import { StoryRecommendationPanel } from '@/components/story/StoryRecommendationPanel';
import type { ApplicationCard } from '@/types/workflow';

/**
 * Embeds the real behavioral-competency + STAR-story tooling (Phase 7.15) scoped to this
 * application's company/role, instead of the omitted "Java readiness"/"missing projects" widgets
 * (no per-language or per-project data exists anywhere in this codebase — see the plan's
 * omitted-sections list). Both embedded panels are dark-safe by design (render nothing/empty
 * state when the feature flag is off).
 */
export function InterviewReadinessPanel({ card }: { card: ApplicationCard }) {
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2 text-sm font-semibold text-foreground">
        <GraduationCap className="h-4 w-4 text-primary" /> Interview readiness
      </div>
      <StoryRecommendationPanel defaultCompanyName={card.company ?? ''} defaultTargetRole={card.jobTitle ?? ''} />
      <CompetencyWidget />
    </div>
  );
}
