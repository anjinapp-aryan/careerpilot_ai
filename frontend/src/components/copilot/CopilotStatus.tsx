import { cn } from '@/lib/cn';
import type { CopilotState } from './CopilotAvatar';

const STATUS: Record<CopilotState, { label: string; dot: string; live: boolean }> = {
  ready: { label: 'Online', dot: 'bg-success', live: true },
  thinking: { label: 'Thinking…', dot: 'bg-secondary', live: true },
  processing: { label: 'Working…', dot: 'bg-primary', live: true },
  success: { label: 'Online', dot: 'bg-success', live: true },
  error: { label: 'Connection issue', dot: 'bg-danger', live: false },
};

export interface CopilotStatusProps {
  state?: CopilotState;
  className?: string;
}

/** Live presence indicator for the assistant — a pulsing dot + status label. */
export function CopilotStatus({ state = 'ready', className }: CopilotStatusProps) {
  const s = STATUS[state];
  return (
    <span className={cn('inline-flex items-center gap-1.5 text-[11px] font-medium text-muted-foreground', className)}>
      <span className="relative flex h-2 w-2">
        {s.live && (
          <span className={cn('absolute inline-flex h-full w-full animate-ping rounded-full opacity-60', s.dot)} />
        )}
        <span className={cn('relative inline-flex h-2 w-2 rounded-full', s.dot)} />
      </span>
      {s.label}
    </span>
  );
}
