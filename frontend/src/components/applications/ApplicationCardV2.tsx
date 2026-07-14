import { useDraggable } from '@dnd-kit/core';
import { motion } from 'framer-motion';
import { Building2, GripVertical, Info, MapPin, Star } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { ApplicationHealthBadge } from './ApplicationHealthBadge';
import { cn } from '@/lib/cn';
import type { ApplicationCard } from '@/types/workflow';

/** Same hash-to-color-palette pattern as `Jobs.tsx`'s `companyColor()` — no external logo service. */
function companyColor(name: string) {
  const palette = ['bg-primary/10 text-primary', 'bg-secondary/10 text-secondary', 'bg-success/10 text-success', 'bg-warning/10 text-warning'];
  let h = 0;
  for (const c of name) h = (h * 31 + c.charCodeAt(0)) % palette.length;
  return palette[h];
}

const PRIORITY_DOT: Record<string, string> = { HIGH: 'bg-danger', MEDIUM: 'bg-warning', LOW: 'bg-muted-foreground' };

export function ApplicationCardV2({
  card,
  overlay,
  selected,
  onToggleSelect,
  onDetails,
  onToggleFavorite,
}: {
  card: ApplicationCard;
  overlay?: boolean;
  selected?: boolean;
  onToggleSelect?: () => void;
  onDetails?: () => void;
  onToggleFavorite?: () => void;
}) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: card.id });
  const company = card.company ?? `#${card.jobId.slice(0, 8)}`;

  return (
    <motion.div
      ref={overlay ? undefined : setNodeRef}
      {...(overlay ? {} : attributes)}
      {...(overlay ? {} : listeners)}
      layout
      className={cn(
        'group relative rounded-lg border bg-card p-3 shadow-xs',
        selected ? 'border-primary ring-1 ring-primary/40' : 'border-border',
        overlay ? 'rotate-2 shadow-lg' : 'cursor-grab active:cursor-grabbing hover:shadow-md',
        isDragging && !overlay && 'opacity-40',
      )}
    >
      <div className="flex items-start gap-2">
        {onToggleSelect && !overlay && (
          <button
            type="button"
            onPointerDown={(e) => e.stopPropagation()}
            onClick={(e) => {
              e.stopPropagation();
              onToggleSelect();
            }}
            aria-label="Select application"
            className={cn(
              'mt-1 h-4 w-4 shrink-0 rounded border transition-colors',
              selected ? 'border-primary bg-primary' : 'border-border bg-card',
            )}
          />
        )}
        <span className={cn('flex h-8 w-8 shrink-0 items-center justify-center rounded-md text-xs font-semibold', companyColor(company))}>
          {company.slice(0, 2).toUpperCase()}
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium text-foreground">{card.jobTitle ?? 'Job'}</p>
          <p className="flex items-center gap-1 truncate text-xs text-muted-foreground">
            <Building2 className="h-3 w-3" /> {company}
          </p>
          {card.location && (
            <p className="flex items-center gap-1 truncate text-[11px] text-muted-foreground/80">
              <MapPin className="h-3 w-3" /> {card.location}
            </p>
          )}
        </div>
        <span className={cn('mt-1 h-1.5 w-1.5 shrink-0 rounded-full', PRIORITY_DOT[card.priority])} title={`Priority: ${card.priority}`} />
        {onToggleFavorite && !overlay && (
          <button
            type="button"
            onPointerDown={(e) => e.stopPropagation()}
            onClick={(e) => {
              e.stopPropagation();
              onToggleFavorite();
            }}
            aria-label="Toggle favorite"
            className="shrink-0 text-muted-foreground/60 transition-colors hover:text-warning"
          >
            <Star className={cn('h-3.5 w-3.5', card.favorite && 'fill-warning text-warning')} />
          </button>
        )}
      </div>

      <div className="mt-2.5 flex flex-wrap items-center gap-1.5">
        {card.matchScore != null && <Badge tone="primary" className="text-[10px]">Match {card.matchScore}</Badge>}
        {card.atsScore != null && <Badge tone="info" className="text-[10px]">ATS {card.atsScore}</Badge>}
        <ApplicationHealthBadge status={card.healthStatus} className="text-[10px]" />
        {card.remoteType && <Badge tone="neutral" className="text-[10px]">{card.remoteType}</Badge>}
      </div>

      {!overlay && onDetails && (
        <button
          type="button"
          onPointerDown={(e) => e.stopPropagation()}
          onClick={(e) => {
            e.stopPropagation();
            onDetails();
          }}
          aria-label="View application details"
          className="absolute right-2 top-2 rounded-md p-1 text-muted-foreground/0 transition-colors group-hover:text-muted-foreground/60 hover:!bg-muted hover:!text-foreground"
        >
          <Info className="h-3.5 w-3.5" />
        </button>
      )}

      <GripVertical className="pointer-events-none absolute bottom-2 right-2 h-3.5 w-3.5 text-muted-foreground/20 group-hover:text-muted-foreground/40" />
    </motion.div>
  );
}
