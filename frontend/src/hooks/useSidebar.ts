import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface SidebarState {
  /** Desktop: rail collapsed to icons only. Persisted. */
  collapsed: boolean;
  /** Mobile: drawer visibility. Not persisted. */
  mobileOpen: boolean;
  toggleCollapsed: () => void;
  setMobileOpen: (open: boolean) => void;
}

export const useSidebar = create<SidebarState>()(
  persist(
    (set, get) => ({
      collapsed: false,
      mobileOpen: false,
      toggleCollapsed: () => set({ collapsed: !get().collapsed }),
      setMobileOpen: (mobileOpen) => set({ mobileOpen }),
    }),
    {
      name: 'careerpilot-sidebar',
      partialize: (s) => ({ collapsed: s.collapsed }),
    },
  ),
);
