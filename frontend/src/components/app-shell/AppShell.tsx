import { Outlet, useLocation } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { Sidebar } from './Sidebar';
import { TopBar } from './TopBar';
import { CommandPalette } from './CommandPalette';
import { CopilotPanel } from '@/components/copilot/CopilotPanel';
import { useSidebar } from '@/hooks/useSidebar';

/**
 * Top-level authenticated layout: fixed dark sidebar + sticky top bar over a
 * light, scrollable content area. Hosts the global command palette and a
 * mobile navigation drawer. Page changes get a subtle fade/slide transition.
 */
export default function AppShell() {
  const { pathname } = useLocation();
  const mobileOpen = useSidebar((s) => s.mobileOpen);
  const setMobileOpen = useSidebar((s) => s.setMobileOpen);

  return (
    <div className="flex h-screen overflow-hidden bg-background">
      {/* Desktop sidebar */}
      <div className="hidden lg:flex">
        <Sidebar />
      </div>

      {/* Mobile drawer */}
      <AnimatePresence>
        {mobileOpen && (
          <div className="fixed inset-0 z-50 lg:hidden">
            <motion.div
              className="absolute inset-0 bg-foreground/40 backdrop-blur-sm"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setMobileOpen(false)}
            />
            <motion.div
              className="absolute inset-y-0 left-0"
              initial={{ x: -300 }}
              animate={{ x: 0 }}
              exit={{ x: -300 }}
              transition={{ duration: 0.25, ease: 'easeOut' }}
            >
              <Sidebar mobile onNavigate={() => setMobileOpen(false)} />
            </motion.div>
          </div>
        )}
      </AnimatePresence>

      {/* Main column */}
      <div className="flex min-w-0 flex-1 flex-col">
        <TopBar />
        <main className="flex-1 overflow-y-auto">
          <AnimatePresence mode="wait">
            <motion.div
              key={pathname}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -8 }}
              transition={{ duration: 0.2, ease: 'easeOut' }}
              className="mx-auto w-full max-w-[1400px] px-4 py-6 md:px-8 md:py-8"
            >
              <Outlet />
            </motion.div>
          </AnimatePresence>
        </main>
      </div>

      {/* Persistent context-aware AI Copilot (right rail on desktop, drawer on mobile) */}
      <CopilotPanel />

      <CommandPalette />
    </div>
  );
}
