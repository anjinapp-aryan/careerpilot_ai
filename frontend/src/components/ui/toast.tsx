import {
  createContext,
  useCallback,
  useContext,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { CheckCircle2, AlertTriangle, XCircle, Info, X } from 'lucide-react';
import { cn } from '@/lib/cn';

export type ToastVariant = 'default' | 'success' | 'warning' | 'error';

export interface ToastOptions {
  title: string;
  description?: string;
  variant?: ToastVariant;
  duration?: number;
  /** Optional action button, e.g. an Undo. */
  action?: { label: string; onClick: () => void };
}

interface ToastItem extends ToastOptions {
  id: number;
}

interface ToastCtx {
  toast: (opts: ToastOptions) => void;
}

const Ctx = createContext<ToastCtx | null>(null);

export function useToast(): ToastCtx {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('useToast must be used within <ToastProvider>');
  return ctx;
}

const VARIANT_META: Record<
  ToastVariant,
  { icon: typeof Info; iconClass: string; accent: string }
> = {
  default: { icon: Info, iconClass: 'text-secondary', accent: 'bg-secondary' },
  success: { icon: CheckCircle2, iconClass: 'text-success', accent: 'bg-success' },
  warning: { icon: AlertTriangle, iconClass: 'text-warning', accent: 'bg-warning' },
  error: { icon: XCircle, iconClass: 'text-danger', accent: 'bg-danger' },
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const idRef = useRef(0);

  const dismiss = useCallback((id: number) => {
    setToasts((t) => t.filter((x) => x.id !== id));
  }, []);

  const toast = useCallback(
    (opts: ToastOptions) => {
      const id = ++idRef.current;
      const item: ToastItem = { id, variant: 'default', duration: 4500, ...opts };
      setToasts((t) => [...t, item]);
      if (item.duration && item.duration > 0) {
        window.setTimeout(() => dismiss(id), item.duration);
      }
    },
    [dismiss],
  );

  return (
    <Ctx.Provider value={{ toast }}>
      {children}
      {typeof document !== 'undefined' &&
        createPortal(
          <div className="pointer-events-none fixed inset-x-0 top-0 z-[200] flex flex-col items-end gap-2 p-4 sm:inset-x-auto sm:right-0">
            <AnimatePresence>
              {toasts.map((t) => {
                const meta = VARIANT_META[t.variant ?? 'default'];
                const Icon = meta.icon;
                return (
                  <motion.div
                    key={t.id}
                    layout
                    initial={{ opacity: 0, x: 32, scale: 0.96 }}
                    animate={{ opacity: 1, x: 0, scale: 1 }}
                    exit={{ opacity: 0, x: 32, scale: 0.96 }}
                    transition={{ duration: 0.22, ease: 'easeOut' }}
                    className="pointer-events-auto relative flex w-[22rem] max-w-[calc(100vw-2rem)] items-start gap-3 overflow-hidden rounded-xl border border-border bg-card p-4 pl-5 shadow-lg"
                    role="status"
                  >
                    <span className={cn('absolute inset-y-0 left-0 w-1', meta.accent)} />
                    <Icon className={cn('mt-0.5 h-5 w-5 shrink-0', meta.iconClass)} />
                    <div className="min-w-0 flex-1">
                      <p className="text-sm font-semibold text-foreground">{t.title}</p>
                      {t.description && (
                        <p className="mt-0.5 text-sm text-muted-foreground">{t.description}</p>
                      )}
                      {t.action && (
                        <button
                          type="button"
                          onClick={() => {
                            t.action!.onClick();
                            dismiss(t.id);
                          }}
                          className="mt-2 text-sm font-semibold text-primary hover:underline"
                        >
                          {t.action.label}
                        </button>
                      )}
                    </div>
                    <button
                      type="button"
                      onClick={() => dismiss(t.id)}
                      aria-label="Dismiss"
                      className="rounded-md p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                    >
                      <X className="h-4 w-4" />
                    </button>
                  </motion.div>
                );
              })}
            </AnimatePresence>
          </div>,
          document.body,
        )}
    </Ctx.Provider>
  );
}
