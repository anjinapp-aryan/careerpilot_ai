import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export interface AuthUser {
  userId: string;
  orgId: string;
  email: string;
  role: string;
  fullName: string;
}

interface AuthState {
  token: string | null;
  user: AuthUser | null;
  /** True when the session was terminated by the server (expired/invalid token),
   *  not by an explicit user logout. Drives the "session expired" toast. */
  sessionExpired: boolean;
  login: (token: string, user: AuthUser) => void;
  /** Explicit user-initiated sign-out. */
  logout: () => void;
  /** Server-initiated termination (401): clear session and flag it for recovery UX. */
  expireSession: () => void;
  /** Acknowledge the expiry flag once the toast has been shown. */
  clearSessionExpired: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      user: null,
      sessionExpired: false,
      login: (token, user) => set({ token, user, sessionExpired: false }),
      logout: () => set({ token: null, user: null, sessionExpired: false }),
      expireSession: () => set({ token: null, user: null, sessionExpired: true }),
      clearSessionExpired: () => set({ sessionExpired: false }),
    }),
    {
      name: 'careerpilot-auth',
      // Persist only credentials — the expiry flag is transient UI state.
      partialize: (s) => ({ token: s.token, user: s.user }),
    },
  ),
);
