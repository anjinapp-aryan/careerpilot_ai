import {
  FileText,
  Gauge,
  Target,
  Sparkles,
  Send,
  CalendarClock,
  Trophy,
  CheckCircle2,
  AlertTriangle,
  type LucideIcon,
} from 'lucide-react';
import type { CopilotPageKey } from '@/types/copilot';

export interface CopilotAction {
  /** Backend action key (drives the specialised system prompt). */
  key: string;
  label: string;
  icon: LucideIcon;
  /** User-facing message sent to the model (also shown as the user's bubble). */
  prompt: string;
}

interface PageConfig {
  page: CopilotPageKey;
  /** Friendly label for the page context chip. */
  label: string;
  icon: LucideIcon;
  actions: CopilotAction[];
}

/**
 * Maps a route pathname to the Copilot's page context and its context-aware
 * quick actions. This is the single place page → capability is defined on the
 * client; it mirrors the actions the backend AgentOrchestrator understands.
 */
export function pageConfigForPath(pathname: string): PageConfig {
  if (pathname.startsWith('/resumes')) {
    return {
      page: 'resume',
      label: 'Resume',
      icon: FileText,
      actions: [
        { key: 'improve_resume', label: 'Improve Resume', icon: FileText, prompt: 'Improve my resume and show me the highest-impact edits.' },
        { key: 'ats_analysis', label: 'ATS Analysis', icon: Gauge, prompt: 'Run an ATS analysis on my resume and tell me what to fix.' },
      ],
    };
  }
  if (pathname.startsWith('/jobs')) {
    return {
      page: 'jobs',
      label: 'Jobs',
      icon: Target,
      actions: [
        { key: 'job_matching', label: 'Job Matching', icon: Target, prompt: 'How well do I match this job, and what gaps should I close?' },
        { key: 'job_explanation', label: 'Explain Job', icon: Sparkles, prompt: 'Explain this job and what it really involves.' },
      ],
    };
  }
  if (pathname.startsWith('/applications')) {
    return {
      page: 'applications',
      label: 'Applications',
      icon: Send,
      actions: [
        { key: 'followup', label: 'Follow-up Tips', icon: CalendarClock, prompt: "What's my best next follow-up for this application?" },
        { key: 'interview_prediction', label: 'Interview Prediction', icon: Trophy, prompt: 'Predict how I will do in interviews for this, and how to prepare.' },
      ],
    };
  }
  if (pathname.startsWith('/workflow')) {
    return {
      page: 'workflow',
      label: 'AI Workflow',
      icon: Sparkles,
      actions: [
        { key: 'explain_results', label: 'Explain Results', icon: CheckCircle2, prompt: 'Explain the results of my latest workflow run.' },
        { key: 'explain_failures', label: 'Explain Failures', icon: AlertTriangle, prompt: 'Why did my workflow run fail or stall, and how do I fix it?' },
      ],
    };
  }
  return {
    page: 'dashboard',
    label: 'Overview',
    icon: Gauge,
    actions: [],
  };
}
