import { useEffect, useRef, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import {
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Loader2,
  AlertTriangle,
  XCircle,
  Circle,
  FileText,
  Search,
  Target,
  MessageSquare,
  TrendingUp,
  DollarSign,
  UserCheck,
  ClipboardList,
} from 'lucide-react';
import { useWorkflowStatus } from '@/hooks/useWorkflowStatus';
import { StatusBadge } from './StatusBadge';
import { StageDetailPanel } from './StageDetailPanel';
import { cn } from '@/lib/cn';
import type { AgentStatus, WorkflowStatusStepperProps, WorkflowAgent } from '@/types/workflow';

// ---------------------------------------------------------------------------
// Status config — token-based so the stepper reads in both light & dark.
// ---------------------------------------------------------------------------

const STATUS_CONFIG: Record<
  AgentStatus,
  { bgClass: string; borderClass: string; iconClass: string; ringClass: string }
> = {
  COMPLETED: {
    bgClass: 'bg-success/10',
    borderClass: 'border-success/40',
    iconClass: 'text-success',
    ringClass: 'ring-success/20',
  },
  ACTIVE: {
    bgClass: 'bg-secondary/10',
    borderClass: 'border-secondary/50',
    iconClass: 'text-secondary',
    ringClass: 'ring-secondary/25',
  },
  PENDING: {
    bgClass: 'bg-muted',
    borderClass: 'border-border',
    iconClass: 'text-muted-foreground',
    ringClass: 'ring-transparent',
  },
  WAITING_FOR_APPROVAL: {
    bgClass: 'bg-warning/10',
    borderClass: 'border-warning/50',
    iconClass: 'text-warning',
    ringClass: 'ring-warning/25',
  },
  FAILED: {
    bgClass: 'bg-danger/10',
    borderClass: 'border-danger/40',
    iconClass: 'text-danger',
    ringClass: 'ring-danger/20',
  },
  REJECTED: {
    bgClass: 'bg-danger/10',
    borderClass: 'border-danger/40',
    iconClass: 'text-danger',
    ringClass: 'ring-danger/20',
  },
};

const AGENT_ICONS: Record<string, React.ElementType> = {
  'Resume Intelligence': FileText,
  'Job Discovery': Search,
  'ATS Optimization': Target,
  'Interview Preparation': MessageSquare,
  'Career Strategy': TrendingUp,
  'Salary Intelligence': DollarSign,
  'Human Approval': UserCheck,
  'Application Tracking': ClipboardList,
  'Resume Export': FileText,
};

function getAgentIcon(name: string): React.ElementType {
  return AGENT_ICONS[name] ?? ClipboardList;
}

/** Render a millisecond duration as a compact human label (e.g. "1.4s", "820ms"). */
function formatDuration(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

// ---------------------------------------------------------------------------
// Skeleton
// ---------------------------------------------------------------------------

function StepperSkeleton({ count = 5 }: { count?: number }) {
  return (
    <div className="space-y-0" aria-busy="true" aria-label="Loading workflow status">
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className="flex gap-4">
          <div className="flex flex-col items-center">
            <div className="shimmer h-10 w-10 rounded-full" />
            {i < count - 1 && <div className="shimmer my-1 w-px flex-1" style={{ minHeight: 32 }} />}
          </div>
          <div className="flex-1 pb-8 pt-1.5">
            <div className="shimmer mb-2 h-4 w-36 rounded" />
            <div className="shimmer h-3 w-20 rounded" />
          </div>
        </div>
      ))}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Active-state ornaments
// ---------------------------------------------------------------------------

function ActivePulse() {
  return (
    <>
      <motion.span
        className="absolute inset-0 rounded-full border-2 border-secondary"
        animate={{ scale: [1, 1.5], opacity: [0.5, 0] }}
        transition={{ duration: 1.4, repeat: Infinity, ease: 'easeOut' }}
      />
      <motion.span
        className="absolute inset-0 rounded-full border border-secondary"
        animate={{ scale: [1, 1.3], opacity: [0.35, 0] }}
        transition={{ duration: 1.4, repeat: Infinity, ease: 'easeOut', delay: 0.3 }}
      />
    </>
  );
}

function ApprovalPulse() {
  return (
    <motion.span
      className="absolute inset-0 rounded-full border-2 border-warning"
      animate={{ opacity: [0.3, 0.9, 0.3] }}
      transition={{ duration: 1.8, repeat: Infinity, ease: 'easeInOut' }}
    />
  );
}

// ---------------------------------------------------------------------------
// Step icon
// ---------------------------------------------------------------------------

function StepIcon({ agent, index }: { agent: WorkflowAgent; index: number }) {
  const cfg = STATUS_CONFIG[agent.status];
  const AgentIcon = getAgentIcon(agent.name);

  let Glyph: React.ElementType = AgentIcon;
  if (agent.status === 'COMPLETED') Glyph = CheckCircle2;
  else if (agent.status === 'FAILED' || agent.status === 'REJECTED') Glyph = XCircle;
  else if (agent.status === 'WAITING_FOR_APPROVAL') Glyph = AlertTriangle;
  else if (agent.status === 'PENDING') Glyph = AgentIcon ?? Circle;

  return (
    <motion.div
      className={cn(
        'relative flex h-10 w-10 shrink-0 items-center justify-center rounded-full border ring-4',
        cfg.bgClass,
        cfg.borderClass,
        cfg.ringClass,
      )}
      initial={{ scale: 0.8, opacity: 0 }}
      animate={{ scale: 1, opacity: 1 }}
      transition={{ duration: 0.3, delay: index * 0.06 }}
      aria-hidden="true"
    >
      {agent.status === 'ACTIVE' && <ActivePulse />}
      {agent.status === 'WAITING_FOR_APPROVAL' && <ApprovalPulse />}

      {agent.status === 'ACTIVE' ? (
        <Loader2 className={cn('h-4 w-4 animate-spin', cfg.iconClass)} />
      ) : (
        <Glyph className={cn('h-4 w-4', cfg.iconClass)} />
      )}
    </motion.div>
  );
}

// ---------------------------------------------------------------------------
// Connector
// ---------------------------------------------------------------------------

function VerticalConnector({ fromStatus }: { fromStatus: AgentStatus }) {
  const done = fromStatus === 'COMPLETED';
  const failed = fromStatus === 'FAILED';

  return (
    <div className="relative mx-auto w-px flex-1 overflow-hidden" style={{ minHeight: 28 }}>
      <div className="absolute inset-0 bg-border" />
      {(done || failed) && (
        <motion.div
          className={cn('absolute inset-0', failed ? 'bg-danger/50' : 'bg-success/60')}
          initial={{ scaleY: 0 }}
          animate={{ scaleY: 1 }}
          style={{ originY: 0 }}
          transition={{ duration: 0.4 }}
        />
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Approval banner
// ---------------------------------------------------------------------------

function ApprovalBanner() {
  return (
    <motion.div
      className="mt-2 flex items-center gap-2 rounded-lg border border-warning/30 bg-warning/10 px-3 py-2 text-xs text-warning"
      initial={{ opacity: 0, y: -4 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3 }}
      role="alert"
    >
      <AlertTriangle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
      <span>Waiting for Human Approval — action required</span>
    </motion.div>
  );
}

// ---------------------------------------------------------------------------
// Vertical layout
// ---------------------------------------------------------------------------

function VerticalStepper({
  agents,
  state,
  expandedStage,
  onToggleStage,
  stageRefs,
}: {
  agents: WorkflowAgent[];
  state?: Record<string, unknown>;
  expandedStage: string | null;
  onToggleStage: (stageName: string) => void;
  stageRefs: React.MutableRefObject<Map<string, HTMLLIElement>>;
}) {
  return (
    <ol className="space-y-0" aria-label="Workflow steps">
      {agents.map((agent, i) => {
        const isLast = i === agents.length - 1;
        const isExpanded = expandedStage === agent.name;
        const timestamp = agent.completedAt
          ? new Intl.DateTimeFormat('en-US', {
              hour: '2-digit',
              minute: '2-digit',
              second: '2-digit',
              hour12: false,
            }).format(new Date(agent.completedAt))
          : null;

        return (
          <motion.li
            key={agent.name}
            ref={(el) => {
              if (el) stageRefs.current.set(agent.name, el);
              else stageRefs.current.delete(agent.name);
            }}
            className="flex gap-4"
            initial={{ opacity: 0, x: -8 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ duration: 0.3, delay: i * 0.07 }}
          >
            <div className="flex w-10 shrink-0 flex-col items-center">
              <StepIcon agent={agent} index={i} />
              {!isLast && <VerticalConnector fromStatus={agent.status} />}
            </div>

            <div className={cn('flex-1 pt-1.5', isLast ? 'pb-0' : 'pb-7')}>
              <button
                type="button"
                className="flex w-full flex-wrap items-center gap-2 text-left"
                onClick={() => onToggleStage(agent.name)}
                aria-expanded={isExpanded}
              >
                <span
                  className={cn(
                    'text-sm font-medium',
                    agent.status === 'PENDING' ? 'text-muted-foreground' : 'text-foreground',
                  )}
                >
                  {agent.name}
                </span>
                <StatusBadge status={agent.status} />
                <span className="ml-auto text-muted-foreground">
                  {isExpanded ? <ChevronUp className="h-3.5 w-3.5" /> : <ChevronDown className="h-3.5 w-3.5" />}
                </span>
              </button>

              {(timestamp || agent.provider || agent.durationMs != null) && (
                <p className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs tabular-nums text-muted-foreground">
                  {timestamp && <span>{timestamp}</span>}
                  {agent.provider && (
                    <span className="rounded bg-muted px-1.5 py-0.5 font-medium uppercase tracking-wide text-foreground/70">
                      {agent.provider}
                    </span>
                  )}
                  {agent.durationMs != null && <span>{formatDuration(agent.durationMs)}</span>}
                </p>
              )}

              <AnimatePresence>
                {agent.status === 'WAITING_FOR_APPROVAL' && <ApprovalBanner />}
              </AnimatePresence>

              <AnimatePresence initial={false}>
                {isExpanded && (
                  <motion.div
                    initial={{ height: 0, opacity: 0 }}
                    animate={{ height: 'auto', opacity: 1 }}
                    exit={{ height: 0, opacity: 0 }}
                    transition={{ duration: 0.2, ease: 'easeInOut' }}
                    className="overflow-hidden"
                  >
                    <div className="mt-2 rounded-lg border border-border bg-muted/20 p-3">
                      <StageDetailPanel stageName={agent.name} state={state} />
                    </div>
                  </motion.div>
                )}
              </AnimatePresence>
            </div>
          </motion.li>
        );
      })}
    </ol>
  );
}

// ---------------------------------------------------------------------------
// Horizontal layout
// ---------------------------------------------------------------------------

function HorizontalStepper({
  agents,
  onStageNavigate,
}: {
  agents: WorkflowAgent[];
  onStageNavigate?: (stageName: string) => void;
}) {
  return (
    <div role="list" aria-label="Workflow steps" className="w-full">
      <div className="flex items-center">
        {agents.map((agent, i) => {
          const isLast = i === agents.length - 1;
          const done = agent.status === 'COMPLETED';
          const failed = agent.status === 'FAILED';
          return (
            <div key={agent.name} className="flex flex-1 items-center">
              <motion.button
                type="button"
                role="listitem"
                aria-label={`${agent.name}: ${agent.status}. View details in the execution timeline.`}
                className="flex flex-col items-center"
                onClick={() => onStageNavigate?.(agent.name)}
                initial={{ opacity: 0, y: 6 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.3, delay: i * 0.07 }}
              >
                <StepIcon agent={agent} index={i} />
              </motion.button>
              {!isLast && (
                <div className="relative mx-1 h-px flex-1 overflow-hidden bg-border">
                  {(done || failed) && (
                    <div className={cn('absolute inset-0', failed ? 'bg-danger/50' : 'bg-success/60')} />
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>

      <div className="mt-3 flex items-start">
        {agents.map((agent) => (
          <div key={agent.name} className="flex flex-1 justify-center px-1">
            <button
              type="button"
              onClick={() => onStageNavigate?.(agent.name)}
              className={cn(
                'text-center text-[10px] font-medium leading-tight',
                agent.status === 'PENDING' ? 'text-muted-foreground' : 'text-foreground',
              )}
              title={agent.name}
            >
              {agent.name}
            </button>
          </div>
        ))}
      </div>

      <AnimatePresence>
        {agents.some((a) => a.status === 'WAITING_FOR_APPROVAL') && (
          <div className="mt-4">
            <ApprovalBanner />
          </div>
        )}
      </AnimatePresence>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Main export
// ---------------------------------------------------------------------------

export function WorkflowStatusStepper({
  workflowId,
  agents: agentsProp,
  variant = 'vertical',
  className = '',
  focusStage,
  focusNonce,
  onStageNavigate,
}: WorkflowStatusStepperProps) {
  const { data, isLoading, isError } = useWorkflowStatus(workflowId);
  const agents: WorkflowAgent[] = data?.agents ?? agentsProp ?? [];
  const state = data?.state;

  // One stage open at a time, per stepper instance — co-locating this here (rather
  // than lifting it into the caller) gives "one open stage per run card" for free,
  // since each RunCard mounts its own WorkflowStatusStepper instance.
  const [expandedStage, setExpandedStage] = useState<string | null>(null);
  const stageRefs = useRef<Map<string, HTMLLIElement>>(new Map());

  // Externally requested focus (e.g. a click on the top horizontal pipeline):
  // expand that stage here and scroll it into view. `focusNonce` lets the same
  // stage be re-focused even if it's already open.
  useEffect(() => {
    if (!focusStage) return;
    setExpandedStage(focusStage);
    const el = stageRefs.current.get(focusStage);
    el?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusStage, focusNonce]);

  const toggleStage = (stageName: string) => setExpandedStage((s) => (s === stageName ? null : stageName));

  const shell = 'rounded-xl border border-border bg-muted/30 p-5';

  if (isLoading && agents.length === 0) {
    return (
      <div className={cn(shell, className)}>
        <StepperSkeleton count={8} />
      </div>
    );
  }

  if (isError && agents.length === 0) {
    return (
      <div
        className={cn('rounded-xl border border-danger/30 bg-danger/5 p-5 text-center text-sm text-danger', className)}
        role="alert"
      >
        Failed to load workflow status.
      </div>
    );
  }

  if (agents.length === 0) {
    return (
      <div className={cn(shell, 'text-center text-sm text-muted-foreground', className)}>
        No agent data available yet.
      </div>
    );
  }

  return (
    <div className={cn(shell, className)} role="region" aria-label={`Workflow ${workflowId} status`}>
      {isLoading && (
        <div className="mb-3 flex items-center gap-1.5 text-xs text-muted-foreground">
          <Loader2 className="h-3 w-3 animate-spin" aria-hidden="true" />
          <span>Syncing…</span>
        </div>
      )}

      <AnimatePresence mode="wait">
        {variant === 'vertical' ? (
          <VerticalStepper
            key="vertical"
            agents={agents}
            state={state}
            expandedStage={expandedStage}
            onToggleStage={toggleStage}
            stageRefs={stageRefs}
          />
        ) : (
          <HorizontalStepper key="horizontal" agents={agents} onStageNavigate={onStageNavigate} />
        )}
      </AnimatePresence>
    </div>
  );
}
