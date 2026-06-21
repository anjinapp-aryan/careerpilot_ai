import {
  CheckCircle2,
  Loader2,
  Clock,
  AlertTriangle,
  XCircle,
} from 'lucide-react';
import type { AgentStatus } from '@/types/workflow';

interface StatusBadgeProps {
  status: AgentStatus;
  className?: string;
}

const CONFIG: Record<
  AgentStatus,
  { label: string; icon: React.ElementType; classes: string; iconClasses: string }
> = {
  COMPLETED: {
    label: 'Completed',
    icon: CheckCircle2,
    classes: 'bg-success/10 text-success ring-1 ring-inset ring-success/25',
    iconClasses: 'text-success',
  },
  ACTIVE: {
    label: 'Running',
    icon: Loader2,
    classes: 'bg-secondary/10 text-secondary ring-1 ring-inset ring-secondary/25',
    iconClasses: 'text-secondary animate-spin',
  },
  PENDING: {
    label: 'Pending',
    icon: Clock,
    classes: 'bg-muted text-muted-foreground ring-1 ring-inset ring-border',
    iconClasses: 'text-muted-foreground',
  },
  WAITING_FOR_APPROVAL: {
    label: 'Awaiting Approval',
    icon: AlertTriangle,
    classes: 'bg-warning/10 text-warning ring-1 ring-inset ring-warning/30',
    iconClasses: 'text-warning',
  },
  FAILED: {
    label: 'Failed',
    icon: XCircle,
    classes: 'bg-danger/10 text-danger ring-1 ring-inset ring-danger/25',
    iconClasses: 'text-danger',
  },
  REJECTED: {
    label: 'Rejected',
    icon: XCircle,
    classes: 'bg-danger/10 text-danger ring-1 ring-inset ring-danger/25',
    iconClasses: 'text-danger',
  },
};

export function StatusBadge({ status, className = '' }: StatusBadgeProps) {
  const { label, icon: Icon, classes, iconClasses } = CONFIG[status];

  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium ${classes} ${className}`}
      role="status"
      aria-label={`Status: ${label}`}
    >
      <Icon className={`h-3 w-3 shrink-0 ${iconClasses}`} aria-hidden="true" />
      {label}
    </span>
  );
}
