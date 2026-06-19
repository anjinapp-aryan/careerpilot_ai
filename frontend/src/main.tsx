import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import App from './App';
import { ToastProvider } from '@/components/ui/toast';
import { applyThemeClass, readStoredMode, useTheme, watchSystemTheme } from '@/lib/theme';
import './index.css';

// Apply persisted theme synchronously before React mounts, then keep the
// store + <html> class in sync (and reactive to OS preference for `system`).
const storedMode = readStoredMode();
applyThemeClass(storedMode);
useTheme.setState({ mode: storedMode, resolved: document.documentElement.classList.contains('dark') ? 'dark' : 'light' });
watchSystemTheme();

const qc = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, refetchOnWindowFocus: false } },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={qc}>
      <ToastProvider>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </ToastProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
