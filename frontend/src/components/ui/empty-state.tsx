import type { LucideIcon } from 'lucide-react';
import { cn } from '@/lib/cn';

export interface EmptyStateProps {
  icon?: LucideIcon;
  title: string;
  description?: string;
  action?: React.ReactNode;
  className?: string;
  /** Compact variant for inline cards/widgets. */
  compact?: boolean;
}

export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  className,
  compact,
}: EmptyStateProps) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center rounded-xl border border-dashed border-border bg-muted/30 text-center',
        compact ? 'gap-2 p-6' : 'gap-3 p-10',
        className,
      )}
    >
      {Icon && (
        <div
          className={cn(
            'flex items-center justify-center rounded-full bg-card text-muted-foreground shadow-sm ring-1 ring-border',
            compact ? 'h-10 w-10' : 'h-14 w-14',
          )}
        >
          <Icon className={compact ? 'h-5 w-5' : 'h-7 w-7'} aria-hidden="true" />
        </div>
      )}
      <div className="space-y-1">
        <h3 className={cn('font-semibold text-foreground', compact ? 'text-sm' : 'text-base')}>
          {title}
        </h3>
        {description && (
          <p className="mx-auto max-w-sm text-sm text-muted-foreground">{description}</p>
        )}
      </div>
      {action && <div className="mt-1">{action}</div>}
    </div>
  );
}
