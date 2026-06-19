import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type ThemeMode = 'light' | 'dark' | 'system';

const STORAGE_KEY = 'careerpilot-theme';

function systemPrefersDark(): boolean {
  if (typeof window === 'undefined' || !window.matchMedia) return false;
  return window.matchMedia('(prefers-color-scheme: dark)').matches;
}

/** Resolve a (possibly `system`) mode to the concrete theme to render. */
export function resolveTheme(mode: ThemeMode): 'light' | 'dark' {
  return mode === 'system' ? (systemPrefersDark() ? 'dark' : 'light') : mode;
}

/** Apply the resolved theme to <html> (class + color-scheme). */
export function applyThemeClass(mode: ThemeMode): void {
  if (typeof document === 'undefined') return;
  const resolved = resolveTheme(mode);
  const root = document.documentElement;
  root.classList.toggle('dark', resolved === 'dark');
  root.style.colorScheme = resolved;
}

interface ThemeState {
  mode: ThemeMode;
  /** Concrete theme currently applied (kept in sync for UI affordances). */
  resolved: 'light' | 'dark';
  setMode: (mode: ThemeMode) => void;
  /** Flip between light and dark explicitly (drops `system`). */
  toggle: () => void;
}

export const useTheme = create<ThemeState>()(
  persist(
    (set, get) => ({
      mode: 'light',
      resolved: 'light',
      setMode: (mode) => {
        applyThemeClass(mode);
        set({ mode, resolved: resolveTheme(mode) });
      },
      toggle: () => {
        const next = resolveTheme(get().mode) === 'dark' ? 'light' : 'dark';
        applyThemeClass(next);
        set({ mode: next, resolved: next });
      },
    }),
    {
      name: STORAGE_KEY,
      onRehydrateStorage: () => (state) => {
        if (state) {
          applyThemeClass(state.mode);
          state.resolved = resolveTheme(state.mode);
        }
      },
    },
  ),
);

/**
 * Read the persisted mode synchronously (before React mounts) so the correct
 * theme is painted on first frame — avoids a light/dark flash on reload.
 */
export function readStoredMode(): ThemeMode {
  if (typeof localStorage === 'undefined') return 'light';
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return 'light';
    const mode = JSON.parse(raw)?.state?.mode;
    return mode === 'light' || mode === 'dark' || mode === 'system' ? mode : 'light';
  } catch {
    return 'light';
  }
}

/** Keep `system` mode reactive to OS preference changes. */
export function watchSystemTheme(): () => void {
  if (typeof window === 'undefined' || !window.matchMedia) return () => {};
  const mq = window.matchMedia('(prefers-color-scheme: dark)');
  const handler = () => {
    if (useTheme.getState().mode === 'system') applyThemeClass('system');
  };
  mq.addEventListener('change', handler);
  return () => mq.removeEventListener('change', handler);
}
