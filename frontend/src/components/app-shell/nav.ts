import {
  LayoutDashboard,
  FileText,
  Briefcase,
  KanbanSquare,
  Sparkles,
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
