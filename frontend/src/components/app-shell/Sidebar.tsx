import { NavLink } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { PanelLeftClose, PanelLeft, Sparkles, Compass } from 'lucide-react';
import { NAV_GROUPS } from './nav';
import { useSidebar } from '@/hooks/useSidebar';
import { useAuthStore } from '@/lib/auth';
import { Tooltip } from '@/components/ui/tooltip';
import { cn } from '@/lib/cn';

const ADMIN_ROLES = new Set(['OWNER', 'ADMIN']);

const EXPANDED = 280;
const COLLAPSED = 80;

interface SidebarProps {
  /** When true, render the always-expanded mobile drawer variant. */
  mobile?: boolean;
  onNavigate?: () => void;
}

/**
 * The primary navigation rail. Dark in both themes (Linear/Stripe style),
 * collapsible to an icon-only rail on desktop, and rendered full-width inside
 * the mobile drawer. Width animates between 80px / 280px.
 */
export function Sidebar({ mobile = false, onNavigate }: SidebarProps) {
  const collapsed = useSidebar((s) => s.collapsed) && !mobile;
  const toggleCollapsed = useSidebar((s) => s.toggleCollapsed);
  const isAdmin = ADMIN_ROLES.has(useAuthStore((s) => s.user?.role) ?? '');

  return (
    <motion.aside
      initial={false}
      animate={{ width: collapsed ? COLLAPSED : EXPANDED }}
      transition={{ duration: 0.22, ease: 'easeOut' }}
      className={cn(
        'flex h-full flex-col bg-sidebar text-sidebar-foreground',
        mobile ? 'w-[280px]' : 'border-r border-sidebar-border',
      )}
      aria-label="Primary"
    >
      {/* Brand */}
      <div className={cn('flex h-16 items-center gap-2.5 px-5', collapsed && 'justify-center px-0')}>
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-primary text-primary-foreground shadow-md shadow-primary/30">
          <Compass className="h-5 w-5" />
        </span>
        <AnimatePresence initial={false}>
          {!collapsed && (
            <motion.span
              initial={{ opacity: 0, x: -6 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -6 }}
              transition={{ duration: 0.15 }}
              className="flex flex-col leading-tight"
            >
              <span className="text-sm font-semibold tracking-tight text-white">CareerPilot</span>
              <span className="text-[11px] font-medium text-sidebar-muted">AI Platform</span>
            </motion.span>
          )}
        </AnimatePresence>
      </div>

      {/* Nav groups */}
      <nav className="flex-1 space-y-6 overflow-y-auto scrollbar-none px-3 py-4">
        {NAV_GROUPS.map((group) => {
          const items = group.items.filter((item) => !item.adminOnly || isAdmin);
          if (items.length === 0) return null;
          return (
          <div key={group.label}>
            <AnimatePresence initial={false}>
              {!collapsed && (
                <motion.p
                  initial={{ opacity: 0 }}
                  animate={{ opacity: 1 }}
                  exit={{ opacity: 0 }}
                  className="mb-1.5 px-3 text-[10px] font-semibold uppercase tracking-wider text-sidebar-muted"
                >
                  {group.label}
                </motion.p>
              )}
            </AnimatePresence>
            <ul className="space-y-1">
              {items.map((item) => {
                const Icon = item.icon;
                const link = (
                  <NavLink
                    to={item.to}
                    end={item.end}
                    onClick={onNavigate}
                    className={({ isActive }) =>
                      cn(
                        'group relative flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors',
                        collapsed && 'justify-center px-0',
                        isActive
                          ? 'bg-sidebar-accent text-white'
                          : 'text-sidebar-muted hover:bg-sidebar-accent/60 hover:text-white',
                      )
                    }
                  >
                    {({ isActive }) => (
                      <>
                        {isActive && (
                          <motion.span
                            layoutId={mobile ? undefined : 'sidebar-active'}
                            className="absolute left-0 top-1/2 h-5 w-1 -translate-y-1/2 rounded-r-full bg-primary"
                          />
                        )}
                        <Icon className="h-[18px] w-[18px] shrink-0" />
                        {!collapsed && <span className="truncate">{item.label}</span>}
                      </>
                    )}
                  </NavLink>
                );
                return (
                  <li key={item.to}>
                    {collapsed ? (
                      <Tooltip content={item.label} side="right">
                        {link}
                      </Tooltip>
                    ) : (
                      link
                    )}
                  </li>
                );
              })}
            </ul>
          </div>
          );
        })}
      </nav>

      {/* Upgrade card + collapse toggle */}
      <div className="space-y-3 px-3 pb-4">
        <AnimatePresence initial={false}>
          {!collapsed && (
            <motion.div
              initial={{ opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: 6 }}
              className="rounded-xl border border-sidebar-border bg-sidebar-accent/50 p-3.5"
            >
              <div className="flex items-center gap-2 text-sm font-semibold text-white">
                <Sparkles className="h-4 w-4 text-primary" />
                Upgrade to Pro
              </div>
              <p className="mt-1 text-xs text-sidebar-muted">
                Unlock unlimited AI workflow runs & deep analytics.
              </p>
              <button className="mt-3 w-full rounded-lg bg-primary py-1.5 text-xs font-semibold text-primary-foreground transition-colors hover:bg-primary/90">
                Upgrade
              </button>
            </motion.div>
          )}
        </AnimatePresence>

        {!mobile && (
          <button
            type="button"
            onClick={toggleCollapsed}
            aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            className={cn(
              'flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium text-sidebar-muted transition-colors hover:bg-sidebar-accent/60 hover:text-white',
              collapsed && 'justify-center px-0',
            )}
          >
            {collapsed ? (
              <PanelLeft className="h-[18px] w-[18px]" />
            ) : (
              <>
                <PanelLeftClose className="h-[18px] w-[18px]" />
                <span>Collapse</span>
              </>
            )}
          </button>
        )}
      </div>
    </motion.aside>
  );
}
