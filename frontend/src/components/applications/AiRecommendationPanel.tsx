import { ArrowRightCircle, Lightbulb, Mail, PhoneCall, RefreshCcw, Sparkles, Users, XCircle } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import type { ApplicationCard, ApplicationRecommendationAction } from '@/types/workflow';

const ICON: Record<ApplicationRecommendationAction, typeof Sparkles> = {
  WAIT: ArrowRightCircle,
  FOLLOW_UP_NOW: Mail,
  WITHDRAW: XCircle,
  REAPPLY_LATER: RefreshCcw,
  IMPROVE_RESUME: Lightbulb,
  IMPROVE_COVER_LETTER: Lightbulb,
  OUTREACH: PhoneCall,
  NETWORK: Users,
};

const LABEL: Record<ApplicationRecommendationAction, string> = {
  WAIT: 'Sit tight',
  FOLLOW_UP_NOW: 'Follow up now',
  WITHDRAW: 'Consider withdrawing',
  REAPPLY_LATER: 'Reapply later',
  IMPROVE_RESUME: 'Improve your resume',
  IMPROVE_COVER_LETTER: 'Improve your cover letter',
  OUTREACH: 'Reach out proactively',
  NETWORK: 'Network with the team',
};

/**
 * Renders the deterministic recommendation produced by
 * `ai.careerpilot.applications.ApplicationRecommendationService` — a rule, not an LLM guess. The
 * reasoning string below is the exact disclosed reasoning the backend computed.
 */
export function AiRecommendationPanel({ card }: { card: ApplicationCard }) {
  const Icon = ICON[card.recommendationAction];
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 text-base">
          <Sparkles className="h-4 w-4 text-primary" />
          Recommendation
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        <Badge tone="primary" className="gap-1.5">
          <Icon className="h-3.5 w-3.5" /> {LABEL[card.recommendationAction]}
        </Badge>
        <p className="text-sm text-muted-foreground">{card.recommendationReasoning}</p>
      </CardContent>
    </Card>
  );
}
