import { useId } from 'react';
import { motion, type TargetAndTransition, type Transition } from 'framer-motion';
import { cn } from '@/lib/cn';

/**
 * The five lifecycle states of the CareerPilot AI assistant. Each maps to a
 * distinct, enterprise-subtle animation + glow treatment.
 */
export type CopilotState = 'ready' | 'thinking' | 'processing' | 'success' | 'error';

/** Eye + glow color per state. Brand indigo/blue; green=success, red=error. */
const PALETTE: Record<CopilotState, { eye: string; glow: string }> = {
  ready: { eye: '#60A5FA', glow: '#6366F1' }, // accent blue eyes, indigo glow
  thinking: { eye: '#60A5FA', glow: '#6366F1' },
  processing: { eye: '#818CF8', glow: '#4F46E5' },
  success: { eye: '#22C55E', glow: '#22C55E' },
  error: { eye: '#F87171', glow: '#EF4444' },
};

const CONTAINER_ANIM: Record<CopilotState, TargetAndTransition> = {
  ready: { y: 0, scale: [1, 1.025, 1] },
  thinking: { y: [0, -2, 0], scale: 1 },
  processing: { y: 0, scale: [1, 1.04, 1] },
  success: { y: 0, scale: [1, 1.08, 1] },
  error: { x: [0, -1.5, 1.5, 0], y: 0 },
};

const CONTAINER_TRANS: Record<CopilotState, Transition> = {
  ready: { duration: 4, repeat: Infinity, ease: 'easeInOut' },
  thinking: { duration: 2, repeat: Infinity, ease: 'easeInOut' },
  processing: { duration: 1.2, repeat: Infinity, ease: 'easeInOut' },
  success: { duration: 0.5, ease: 'easeOut' },
  error: { duration: 0.4, ease: 'easeOut' },
};

export interface CopilotAvatarProps {
  /** Rendered square size in px. */
  size?: number;
  state?: CopilotState;
  /** When false, renders a static mark (no motion/glow) — for chat lists, etc. */
  animated?: boolean;
  className?: string;
}

/**
 * The official CareerPilot AI robot avatar — a custom, theme-agnostic SVG
 * inspired by a friendly desktop companion robot, reskinned to the brand
 * (indigo head rim, dark "intelligence" screen, glowing blue eyes). Works
 * identically in light and dark themes and scales crisply from 24→64px.
 */
export function CopilotAvatar({ size = 40, state = 'ready', animated = true, className }: CopilotAvatarProps) {
  const uid = useId().replace(/[:]/g, '');
  const { eye, glow } = PALETTE[state];
  const headId = `cp-head-${uid}`;
  const screenId = `cp-screen-${uid}`;
  const accentId = `cp-accent-${uid}`;
  const glowId = `cp-glow-${uid}`;

  const pulsing = state === 'thinking' || state === 'processing';

  return (
    <span
      className={cn('relative inline-flex shrink-0 items-center justify-center', className)}
      style={{ width: size, height: size }}
      role="img"
      aria-label="CareerPilot AI assistant"
    >
      {/* Soft brand glow halo */}
      {animated && (
        <motion.span
          aria-hidden="true"
          className="pointer-events-none absolute inset-0 rounded-full"
          style={{ background: `radial-gradient(circle, ${glow}59 0%, transparent 68%)`, filter: 'blur(5px)' }}
          animate={{ opacity: pulsing ? [0.55, 1, 0.55] : [0.4, 0.68, 0.4] }}
          transition={{ duration: state === 'processing' ? 1 : 2.6, repeat: Infinity, ease: 'easeInOut' }}
        />
      )}

      {/* Processing: animated outer orbit ring */}
      {animated && state === 'processing' && (
        <motion.svg
          aria-hidden="true"
          className="absolute inset-0"
          width={size}
          height={size}
          viewBox="0 0 64 64"
          animate={{ rotate: 360 }}
          transition={{ duration: 2, repeat: Infinity, ease: 'linear' }}
        >
          <circle
            cx="32"
            cy="32"
            r="30"
            fill="none"
            stroke={glow}
            strokeOpacity="0.9"
            strokeWidth="2.5"
            strokeLinecap="round"
            strokeDasharray="38 150"
          />
        </motion.svg>
      )}

      <motion.svg
        width={size}
        height={size}
        viewBox="0 0 64 64"
        className="relative"
        animate={animated ? CONTAINER_ANIM[state] : undefined}
        transition={animated ? CONTAINER_TRANS[state] : undefined}
      >
        <defs>
          <linearGradient id={headId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#FFFFFF" />
            <stop offset="100%" stopColor="#E4E8FF" />
          </linearGradient>
          <linearGradient id={screenId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#262252" />
            <stop offset="100%" stopColor="#0B1020" />
          </linearGradient>
          <linearGradient id={accentId} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor="#6366F1" />
            <stop offset="100%" stopColor="#4F46E5" />
          </linearGradient>
          <filter id={glowId} x="-60%" y="-60%" width="220%" height="220%">
            <feGaussianBlur stdDeviation="1.5" result="b" />
            <feMerge>
              <feMergeNode in="b" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>

        {/* Ears */}
        <rect x="5" y="25" width="7" height="15" rx="3.5" fill={`url(#${accentId})`} />
        <rect x="52" y="25" width="7" height="15" rx="3.5" fill={`url(#${accentId})`} />

        {/* Top crest / antenna fin */}
        <rect x="25" y="5" width="14" height="8" rx="4" fill={`url(#${accentId})`} />

        {/* Head shell */}
        <rect x="11" y="12" width="42" height="40" rx="15" fill={`url(#${headId})`} stroke="#C7D2FE" strokeWidth="1.5" />
        {/* Glossy top highlight */}
        <ellipse cx="32" cy="20" rx="15.5" ry="4.5" fill="#FFFFFF" opacity="0.55" />

        {/* Intelligence screen */}
        <rect x="17" y="20" width="30" height="24" rx="12" fill={`url(#${screenId})`} />
        <rect x="20.5" y="22.5" width="23" height="5.5" rx="2.75" fill="#FFFFFF" opacity="0.06" />

        {/* Eyes */}
        <motion.g
          filter={`url(#${glowId})`}
          style={{ transformBox: 'fill-box', transformOrigin: 'center' }}
          animate={
            !animated
              ? undefined
              : state === 'thinking'
                ? { opacity: [1, 0.5, 1] }
                : state === 'processing'
                  ? { opacity: [1, 0.65, 1] }
                  : { opacity: 1, scaleY: [1, 1, 0.12, 1] }
          }
          transition={
            !animated
              ? undefined
              : state === 'thinking'
                ? { duration: 1.1, repeat: Infinity, ease: 'easeInOut' }
                : state === 'processing'
                  ? { duration: 0.7, repeat: Infinity, ease: 'easeInOut' }
                  : { duration: 5.5, repeat: Infinity, times: [0, 0.93, 0.965, 1], ease: 'easeInOut' }
          }
        >
          <rect x="24.5" y="29" width="6" height="7.5" rx="3" fill={eye} />
          <rect x="33.5" y="29" width="6" height="7.5" rx="3" fill={eye} />
          <circle cx="26" cy="30.8" r="1" fill="#FFFFFF" opacity="0.9" />
          <circle cx="35" cy="30.8" r="1" fill="#FFFFFF" opacity="0.9" />
        </motion.g>

        {/* Subtle friendly indicator */}
        <rect x="28.5" y="40" width="7" height="1.8" rx="0.9" fill={eye} opacity="0.4" />
      </motion.svg>
    </span>
  );
}
