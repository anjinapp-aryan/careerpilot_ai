import type { ReactNode } from 'react';
import { CopilotAvatar, type CopilotState } from './CopilotAvatar';
import { CopilotStatus } from './CopilotStatus';

export interface CopilotHeaderProps {
  state?: CopilotState;
  /** Right-aligned action cluster (history / new chat / collapse). */
  right?: ReactNode;
}

/**
 * The Copilot's branded identity header:
 *
 *   [Robot Avatar]  CareerPilot AI
 *                   Career Intelligence Engine
 *                   ● Online
 */
export function CopilotHeader({ state = 'ready', right }: CopilotHeaderProps) {
  return (
    <div className="flex shrink-0 items-center justify-between gap-2 border-b border-border px-3 py-3">
      <div className="flex min-w-0 items-center gap-3">
        <CopilotAvatar size={48} state={state} />
        <div className="min-w-0 leading-tight">
          <p className="truncate text-sm font-semibold text-foreground">CareerPilot AI</p>
          <p className="truncate text-[11px] text-muted-foreground">Career Intelligence Engine</p>
          <CopilotStatus state={state} className="mt-1" />
        </div>
      </div>
      {right && <div className="flex shrink-0 items-center gap-0.5 self-start pt-0.5">{right}</div>}
    </div>
  );
}
