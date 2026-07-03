import { useEffect } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { Command } from 'cmdk';
import { AnimatePresence, motion } from 'framer-motion';
import {
  Search,
  Upload,
  Plus,
  Sparkles,
  Sun,
  Moon,
  Monitor,
  CornerDownLeft,
} from 'lucide-react';
import { NAV_ITEMS } from './nav';
import { useCommandPalette } from '@/hooks/useCommandPalette';
import { useTheme } from '@/lib/theme';
import { useAuthStore } from '@/lib/auth';
import { Kbd } from '@/components/ui/kbd';

const ADMIN_ROLES = new Set(['OWNER', 'ADMIN']);

export function CommandPalette() {
  const navigate = useNavigate();
  const { open, setOpen, toggle } = useCommandPalette();
  const setMode = useTheme((s) => s.setMode);
  const isAdmin = ADMIN_ROLES.has(useAuthStore((s) => s.user?.role) ?? '');
  const navItems = NAV_ITEMS.filter((item) => !item.adminOnly || isAdmin);

  // Global ⌘K / Ctrl-K toggle.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key.toLowerCase() === 'k' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        toggle();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [toggle]);

  function run(action: () => void) {
    setOpen(false);
    // Defer so the dialog closes cleanly before navigation/state changes.
    requestAnimationFrame(action);
  }

  if (typeof document === 'undefined') return null;

  return createPortal(
    <AnimatePresence>
      {open && (
        <div className="fixed inset-0 z-[150] flex items-start justify-center p-4 pt-[12vh]">
          <motion.div
            className="absolute inset-0 bg-foreground/40 backdrop-blur-sm"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.15 }}
            onClick={() => setOpen(false)}
          />
          <motion.div
            initial={{ opacity: 0, scale: 0.97, y: -8 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.97, y: -8 }}
            transition={{ duration: 0.18, ease: 'easeOut' }}
            className="relative z-10 w-full max-w-xl overflow-hidden rounded-2xl border border-border bg-popover text-popover-foreground shadow-xl"
          >
            <Command
              label="Command palette"
              className="[&_[cmdk-group-heading]]:px-3 [&_[cmdk-group-heading]]:py-1.5 [&_[cmdk-group-heading]]:text-xs [&_[cmdk-group-heading]]:font-medium [&_[cmdk-group-heading]]:text-muted-foreground"
            >
              <div className="flex items-center gap-2.5 border-b border-border px-4">
                <Search className="h-4 w-4 shrink-0 text-muted-foreground" />
                <Command.Input
                  autoFocus
                  placeholder="Search or jump to…"
                  className="h-12 w-full bg-transparent text-sm text-foreground outline-none placeholder:text-muted-foreground"
                />
                <Kbd>Esc</Kbd>
              </div>
              <Command.List className="max-h-[22rem] overflow-y-auto p-2">
                <Command.Empty className="px-3 py-8 text-center text-sm text-muted-foreground">
                  No results found.
                </Command.Empty>

                <Command.Group heading="Navigate">
                  {navItems.map((item) => {
                    const Icon = item.icon;
                    return (
                      <Command.Item
                        key={item.to}
                        value={`${item.label} ${item.keywords?.join(' ') ?? ''}`}
                        onSelect={() => run(() => navigate(item.to))}
                        className="flex cursor-pointer items-center gap-2.5 rounded-lg px-3 py-2.5 text-sm text-foreground aria-selected:bg-muted"
                      >
                        <Icon className="h-4 w-4 text-muted-foreground" />
                        <span className="flex-1">{item.label}</span>
                        {item.description && (
                          <span className="hidden text-xs text-muted-foreground sm:block">
                            {item.description}
                          </span>
                        )}
                      </Command.Item>
                    );
                  })}
                </Command.Group>

                <Command.Group heading="Quick actions">
                  <Command.Item
                    value="upload resume new"
                    onSelect={() => run(() => navigate('/resumes?upload=1'))}
                    className="flex cursor-pointer items-center gap-2.5 rounded-lg px-3 py-2.5 text-sm text-foreground aria-selected:bg-muted"
                  >
                    <Upload className="h-4 w-4 text-muted-foreground" />
                    Upload a resume
                  </Command.Item>
                  <Command.Item
                    value="add job new posting"
                    onSelect={() => run(() => navigate('/jobs?new=1'))}
                    className="flex cursor-pointer items-center gap-2.5 rounded-lg px-3 py-2.5 text-sm text-foreground aria-selected:bg-muted"
                  >
                    <Plus className="h-4 w-4 text-muted-foreground" />
                    Add a job
                  </Command.Item>
                  <Command.Item
                    value="start workflow run ai pipeline"
                    onSelect={() => run(() => navigate('/workflow'))}
                    className="flex cursor-pointer items-center gap-2.5 rounded-lg px-3 py-2.5 text-sm text-foreground aria-selected:bg-muted"
                  >
                    <Sparkles className="h-4 w-4 text-muted-foreground" />
                    Start AI workflow
                  </Command.Item>
                </Command.Group>

                <Command.Group heading="Theme">
                  <Command.Item
                    value="theme light mode"
                    onSelect={() => run(() => setMode('light'))}
                    className="flex cursor-pointer items-center gap-2.5 rounded-lg px-3 py-2.5 text-sm text-foreground aria-selected:bg-muted"
                  >
                    <Sun className="h-4 w-4 text-muted-foreground" /> Light theme
                  </Command.Item>
                  <Command.Item
                    value="theme dark mode"
                    onSelect={() => run(() => setMode('dark'))}
                    className="flex cursor-pointer items-center gap-2.5 rounded-lg px-3 py-2.5 text-sm text-foreground aria-selected:bg-muted"
                  >
                    <Moon className="h-4 w-4 text-muted-foreground" /> Dark theme
                  </Command.Item>
                  <Command.Item
                    value="theme system mode auto"
                    onSelect={() => run(() => setMode('system'))}
                    className="flex cursor-pointer items-center gap-2.5 rounded-lg px-3 py-2.5 text-sm text-foreground aria-selected:bg-muted"
                  >
                    <Monitor className="h-4 w-4 text-muted-foreground" /> System theme
                  </Command.Item>
                </Command.Group>
              </Command.List>
              <div className="flex items-center justify-end gap-2 border-t border-border px-3 py-2 text-[11px] text-muted-foreground">
                <span className="flex items-center gap-1">
                  <CornerDownLeft className="h-3 w-3" /> to select
                </span>
              </div>
            </Command>
          </motion.div>
        </div>
      )}
    </AnimatePresence>,
    document.body,
  );
}
