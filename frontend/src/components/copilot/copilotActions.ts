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
  Brain,
  DollarSign,
  ListChecks,
  Rocket,
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
        { key: 'recommendations', label: "Today's Best Jobs", icon: Sparkles, prompt: "What are today's best jobs, and which are must-apply?" },
        { key: 'skills_gap', label: 'Trending Skills', icon: ListChecks, prompt: 'What skills are trending in today’s discovered jobs?' },
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
  if (pathname.startsWith('/recommendations')) {
    return {
      page: 'jobs',
      label: 'Recommendations',
      icon: Target,
      actions: [
        { key: 'recommendations', label: 'Explain My Recommendations', icon: Sparkles, prompt: 'Explain my current recommendations — why these jobs, in this priority order?' },
        { key: 'job_matching', label: 'Match Analysis', icon: Target, prompt: 'How well do I match my top recommended jobs, and what gaps should I close?' },
        { key: 'skills_gap', label: 'Skill Gaps', icon: ListChecks, prompt: 'Which missing skills appear most often across my recommendations?' },
        { key: 'recommendations', label: 'Best Company Matches', icon: Sparkles, prompt: 'Which companies match me best based on today’s discovery run?' },
        { key: 'recommendations', label: "What's Changed", icon: CalendarClock, prompt: "What changed in my recommendations since yesterday's discovery run?" },
      ],
    };
  }
  if (pathname.startsWith('/mission')) {
    return {
      page: 'mission',
      label: 'Career Mission',
      icon: Rocket,
      actions: [
        { key: 'career_guidance', label: 'Should I continue?', icon: Rocket, prompt: 'Should I continue focusing on my current target country, or consider an alternative?' },
        { key: 'career_guidance', label: "What should I do today?", icon: Sparkles, prompt: "What should I do today to move my career mission forward?" },
        { key: 'career_guidance', label: "What's blocking me?", icon: AlertTriangle, prompt: 'What is blocking my mission right now, and how do I unblock it?' },
        { key: 'career_guidance', label: 'Am I ready?', icon: CheckCircle2, prompt: 'Based on my current progress, am I ready to start applying for my target role?' },
      ],
    };
  }
  if (pathname.startsWith('/career')) {
    return {
      page: 'career',
      label: 'Career Intelligence',
      icon: Brain,
      actions: [
        { key: 'career_guidance', label: 'Explain My Career Probability', icon: Brain, prompt: 'Explain my career success, interview, and offer probabilities and what would improve them.' },
        { key: 'salary_guidance', label: 'Salary Guidance', icon: DollarSign, prompt: 'What salary range should I target based on my profile and the market?' },
        { key: 'skills_gap', label: 'Skill Gaps', icon: ListChecks, prompt: 'What skill gaps are holding back my career probability?' },
      ],
    };
  }
  return {
    page: 'dashboard',
    label: 'Overview',
    icon: Gauge,
    actions: [
      { key: 'career_guidance', label: 'Career Advice', icon: Brain, prompt: 'Give me career advice based on my current profile and activity.' },
      { key: 'recommendations', label: 'Explain My Recommendations', icon: Sparkles, prompt: 'Explain my current job recommendations and why they were prioritized this way.' },
      { key: 'recommendations', label: "Today's Best Jobs", icon: Sparkles, prompt: "What are today's best jobs, and which are must-apply?" },
    ],
  };
}
