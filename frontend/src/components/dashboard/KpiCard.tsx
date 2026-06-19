import { ArrowDownRight, ArrowUpRight, Minus, type LucideIcon } from 'lucide-react';
import { motion } from 'framer-motion';
import { Card } from '@/components/ui/card';
import { cn } from '@/lib/cn';

export interface KpiCardProps {
  label: string;
  value: string | number;
  icon: LucideIcon;
  /** Accent tone for the icon chip. */
  tone?: 'primary' | 'success' | 'warning' | 'danger' | 'info';
  /** Signed delta vs the previous period. Omit when not computable. */
  delta?: number | null;
  /** Sublabel shown when no delta is available. */
  hint?: string;
  /** For percentage-style metrics, suffix the value with %. */
  suffix?: string;
}

const TONES: Record<NonNullable<KpiCardProps['tone']>, string> = {
  primary: 'bg-primary/10 text-primary',
  success: 'bg-success/10 text-success',
  warning: 'bg-warning/10 text-warning',
  danger: 'bg-danger/10 text-danger',
  info: 'bg-secondary/10 text-secondary',
};

export function KpiCard({
  label,
  value,
  icon: Icon,
  tone = 'primary',
  delta,
  hint,
  suffix,
}: KpiCardProps) {
  const hasDelta = typeof delta === 'number' && Number.isFinite(delta) && delta !== 0;
  const positive = (delta ?? 0) > 0;

  return (
    <motion.div whileHover={{ y: -3 }} transition={{ duration: 0.18 }}>
      <Card className="p-5 transition-shadow hover:shadow-md">
        <div className="flex items-start justify-between">
          <span className={cn('flex h-10 w-10 items-center justify-center rounded-xl', TONES[tone])}>
            <Icon className="h-5 w-5" />
          </span>
          {hasDelta ? (
            <span
              className={cn(
                'inline-flex items-center gap-0.5 rounded-full px-2 py-0.5 text-xs font-semibold',
                positive ? 'bg-success/10 text-success' : 'bg-danger/10 text-danger',
              )}
            >
              {positive ? <ArrowUpRight className="h-3 w-3" /> : <ArrowDownRight className="h-3 w-3" />}
              {Math.abs(delta!)}
            </span>
          ) : delta === 0 ? (
            <span className="inline-flex items-center gap-0.5 rounded-full bg-muted px-2 py-0.5 text-xs font-semibold text-muted-foreground">
              <Minus className="h-3 w-3" /> 0
            </span>
          ) : null}
        </div>

        <div className="mt-4">
          <div className="flex items-baseline gap-1">
            <span className="text-2xl font-semibold tracking-tight text-foreground tabular-nums">
              {value}
            </span>
            {suffix && <span className="text-sm font-medium text-muted-foreground">{suffix}</span>}
          </div>
          <p className="mt-1 text-sm text-muted-foreground">{label}</p>
          {hint && !hasDelta && delta !== 0 && (
            <p className="mt-0.5 text-xs text-muted-foreground/80">{hint}</p>
          )}
          {hasDelta && (
            <p className="mt-0.5 text-xs text-muted-foreground/80">vs previous run</p>
          )}
        </div>
      </Card>
    </motion.div>
  );
}
