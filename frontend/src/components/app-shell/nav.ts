import {
  LayoutDashboard,
  FileText,
  Briefcase,
  KanbanSquare,
  Sparkles,
  ShieldCheck,
  BookOpen,
  Banknote,
  Brain,
  Radar,
  Building2,
  Rocket,
  History,
  type LucideIcon,
} from 'lucide-react';

export interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  /** Whether the route should match exactly (used for the index route). */
  end?: boolean;
  /** Searchable synonyms for the command palette. */
  keywords?: string[];
  description?: string;
  /** Hidden from the sidebar/command-palette unless the current user has an OWNER/ADMIN role. */
  adminOnly?: boolean;
}

export interface NavGroup {
  label: string;
  items: NavItem[];
}

export const NAV_GROUPS: NavGroup[] = [
  {
    label: 'Overview',
    items: [
      {
        to: '/',
        label: 'Dashboard',
        icon: LayoutDashboard,
        end: true,
        keywords: ['home', 'overview', 'analytics', 'metrics'],
        description: 'Career health, KPIs & insights',
      },
    ],
  },
  {
    label: 'Career',
    items: [
      {
        to: '/mission',
        label: 'Career Mission',
        icon: Rocket,
        keywords: ['dream', 'goal', 'roadmap', 'strategy', 'orchestrator', 'north star'],
        description: 'Your personal AI Career Operating System',
      },
      {
        to: '/resumes',
        label: 'Resumes',
        icon: FileText,
        keywords: ['cv', 'documents', 'ats', 'upload'],
        description: 'Your resume library',
      },
      {
        to: '/jobs',
        label: 'Jobs',
        icon: Briefcase,
        keywords: ['roles', 'openings', 'positions', 'search'],
        description: 'Discover & track openings',
      },
      {
        to: '/applications',
        label: 'Applications',
        icon: KanbanSquare,
        keywords: ['pipeline', 'kanban', 'tracker', 'status'],
        description: 'Application pipeline board',
      },
      {
        to: '/offers',
        label: 'Offers',
        icon: Banknote,
        keywords: ['offer', 'salary', 'compensation', 'negotiation', 'compare'],
        description: 'Offer intelligence & salary negotiation',
      },
    ],
  },
  {
    label: 'AI',
    items: [
      {
        to: '/workflow',
        label: 'AI Workflow',
        icon: Sparkles,
        keywords: ['agent', 'copilot', 'automation', 'pipeline'],
        description: 'Run the multi-agent career pipeline',
      },
      {
        to: '/stories',
        label: 'Story Library',
        icon: BookOpen,
        keywords: ['star', 'behavioral', 'interview', 'stories', 'competency'],
        description: 'STAR stories & behavioral interview prep',
      },
      {
        to: '/ai-memory',
        label: 'AI Learned About You',
        icon: Brain,
        keywords: ['memory', 'preferences', 'confidence', 'trust', 'learned'],
        description: 'What the AI knows, why, and how confident it is',
      },
      {
        to: '/career-journey',
        label: 'Career Journey',
        icon: History,
        keywords: ['timeline', 'journey', 'history', 'events', 'activity'],
        description: 'Your complete professional journey, chronologically',
      },
      {
        to: '/companies',
        label: 'Company Intelligence',
        icon: Building2,
        keywords: ['hiring', 'interview', 'technology', 'compare', 'company', 'knowledge graph'],
        description: 'Hiring, interview, and technology intelligence per company',
      },
    ],
  },
  {
    label: 'Admin',
    items: [
      {
        to: '/admin',
        label: 'Admin Dashboard',
        icon: ShieldCheck,
        keywords: ['provider health', 'discovery', 'skills', 'salary', 'enrichment'],
        description: 'Job discovery & AI enrichment ops',
        adminOnly: true,
      },
      {
        to: '/operations',
        label: 'Operations Center',
        icon: Radar,
        keywords: ['execution', 'retry', 'recovery', 'verification', 'fleet', 'queue', 'automation'],
        description: 'Automation execution, recovery & verification observability',
        adminOnly: true,
      },
    ],
  },
];

export const NAV_ITEMS: NavItem[] = NAV_GROUPS.flatMap((g) => g.items);

/** Human label for a pathname, used by breadcrumbs. */
export function labelForPath(pathname: string): string {
  const match = NAV_ITEMS.find((i) =>
    i.end ? i.to === pathname : pathname.startsWith(i.to) && i.to !== '/',
  );
  return match?.label ?? 'Dashboard';
}
