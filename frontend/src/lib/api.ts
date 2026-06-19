import axios from 'axios';
import { useAuthStore } from '@/lib/auth';

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
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
