import axios from 'axios';
import { useAuthStore } from '@/lib/auth';

export const api = axios.create({
  // Every call site (Login.tsx, Register.tsx, etc.) already includes the
  // "/api" prefix in its own path — baseURL is meant to be just the origin
  // (or empty, for a same-origin reverse-proxy deployment). `??` (not `||`)
  // matters here: an intentional empty string ("" — same-origin, nginx
  // proxies /api/* to the backend) must NOT fall through to the localhost
  // dev default, which `||` would do since "" is falsy.
  baseURL: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080',
  // Without a timeout, a hung backend (dead connection, crash-loop) leaves requests
  // pending indefinitely with no signal to the user. 60s covers Render's own documented
  // free-tier cold-start worst case ("50 seconds or more") — an earlier, tighter 20s
  // value here caused real, successful-but-slow cold-start requests to be killed and
  // misreported as "can't reach the server" even though the backend was fine.
  timeout: 60_000,
});

api.interceptors.request.use((config) => {
  const token = useAuthStore.getState().token;
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

api.interceptors.response.use(
  (r) => r,
  (err) => {
    // A 401 on an authenticated request means the token expired/was invalidated
    // server-side (the backend now returns 401, not 403, for this — see
    // RestAuthenticationEntryPoint). Tear down the session so the app recovers
    // instead of silently failing every call. The `token` guard ensures a
    // failed login attempt (also 401, but with no active session) is left alone.
    if (err?.response?.status === 401 && useAuthStore.getState().token) {
      useAuthStore.getState().expireSession();
    }
    return Promise.reject(err);
  },
);
