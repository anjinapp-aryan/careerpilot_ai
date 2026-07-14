import { CalendarClock } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import type { ApplicationCard } from '@/types/workflow';

/** The single suggested next action + deadline, from `ApplicationNextActionService`. */
export function NextActionCard({ card }: { card: ApplicationCard }) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 text-base">
          <CalendarClock className="h-4 w-4 text-primary" />
          Next action
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-1">
        <p className="text-sm font-medium text-foreground">{card.suggestedNextAction}</p>
        {card.suggestedNextActionAt && (
          <p className="text-xs text-muted-foreground">
            By {new Date(card.suggestedNextActionAt).toLocaleDateString()}
          </p>
        )}
      </CardContent>
    </Card>
  );
}
