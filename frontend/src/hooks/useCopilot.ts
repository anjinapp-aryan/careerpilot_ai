import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface CopilotState {
  /** Desktop: panel collapsed to a 60px icon rail. Persisted. */
  collapsed: boolean;
  /** Mobile: slide-over drawer visibility. Not persisted. */
  mobileOpen: boolean;
  toggleCollapsed: () => void;
  setCollapsed: (v: boolean) => void;
  setMobileOpen: (v: boolean) => void;
}

export const useCopilot = create<CopilotState>()(
  persist(
    (set, get) => ({
      collapsed: false,
      mobileOpen: false,
      toggleCollapsed: () => set({ collapsed: !get().collapsed }),
      setCollapsed: (collapsed) => set({ collapsed }),
      setMobileOpen: (mobileOpen) => set({ mobileOpen }),
    }),
    {
      name: 'careerpilot-copilot',
      partialize: (s) => ({ collapsed: s.collapsed }),
    },
  ),
);
