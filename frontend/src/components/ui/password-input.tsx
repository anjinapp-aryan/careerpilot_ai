import {
  forwardRef,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type InputHTMLAttributes,
  type KeyboardEvent,
} from 'react';
import { AnimatePresence, motion, useAnimationControls } from 'framer-motion';
import { Check, Eye, EyeOff, TriangleAlert, X } from 'lucide-react';
import { cn } from '@/lib/cn';
import { PASSWORD_REQUIREMENTS, getPasswordStrength, type PasswordStrength } from '@/lib/password';

export interface PasswordInputProps
  extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'onChange'> {
  value: string;
  onChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
  /** Truthy renders a red border + one-shot shake + (if a string) the message itself, below the field. */
  error?: string | boolean;
  /** Live weak/fair/good/strong meter. */
  showStrength?: boolean;
  /** Live checklist (8 chars, upper/lower/number/special). */
  showValidation?: boolean;
  /** Show the eye toggle button. Defaults to true. */
  allowToggle?: boolean;
}

const STRENGTH_CONFIG: Record<PasswordStrength, { label: string; barClass: string }> = {
  empty: { label: '', barClass: '' },
  weak: { label: 'Weak', barClass: 'bg-danger' },
  fair: { label: 'Fair', barClass: 'bg-warning' },
  good: { label: 'Good', barClass: 'bg-primary' },
  strong: { label: 'Strong', barClass: 'bg-success' },
};
const STRENGTH_LEVELS: PasswordStrength[] = ['weak', 'fair', 'good', 'strong'];

/**
 * Enterprise-grade password field: show/hide toggle, Caps Lock warning, live
 * strength meter + requirement checklist, and a self-contained error state
 * (red border + one-shot shake + message) — reused across Login, Register,
 * and any future Change/Reset Password flow.
 *
 * Visibility state is local-only (never in a store, never persisted) — it
 * always resets to hidden on mount/refresh, by construction, not by extra
 * code. The `type` attribute toggles on a single, never-remounted <input>,
 * but focus/cursor position do NOT survive that for free — see the
 * useLayoutEffect below for why an explicit restore is required.
 */
export const PasswordInput = forwardRef<HTMLInputElement, PasswordInputProps>(
  (
    {
      value,
      onChange,
      error,
      showStrength = false,
      showValidation = false,
      allowToggle = true,
      disabled,
      className,
      id: idProp,
      onKeyDown: onKeyDownProp,
      onKeyUp: onKeyUpProp,
      ...restProps
    },
    ref,
  ) => {
    const [visible, setVisible] = useState(false);
    const [capsLockOn, setCapsLockOn] = useState(false);
    const generatedId = useId();
    const id = idProp ?? generatedId;
    const shakeControls = useAnimationControls();
    const prevErrorRef = useRef<string | boolean | undefined>(undefined);
    const inputRef = useRef<HTMLInputElement | null>(null);
    const savedSelectionRef = useRef<{ start: number; end: number } | null>(null);
    const isFirstRenderRef = useRef(true);

    // Chromium resets an <input>'s selectionStart/selectionEnd to 0 on a
    // type="password"->"text" (or back) mutation, even when the element
    // never loses focus — restoring the range synchronously in
    // useLayoutEffect (before paint) is NOT enough, because React re-syncs
    // a controlled input's value/selection state again on a tick after the
    // layout-effect phase (verified empirically: setSelectionRange inside
    // this effect reads back correctly immediately, then reads back as 0 a
    // moment later). Deferring to the next animation frame — after that
    // re-sync has already happened — is what actually sticks.
    useLayoutEffect(() => {
      if (isFirstRenderRef.current) {
        isFirstRenderRef.current = false;
        return;
      }
      const el = inputRef.current;
      const sel = savedSelectionRef.current;
      if (el && sel) {
        const raf = requestAnimationFrame(() => {
          el.focus();
          el.setSelectionRange(sel.start, sel.end);
        });
        return () => cancelAnimationFrame(raf);
      }
    }, [visible]);

    function mergeRefs(node: HTMLInputElement | null) {
      inputRef.current = node;
      if (typeof ref === 'function') ref(node);
      else if (ref) (ref as React.MutableRefObject<HTMLInputElement | null>).current = node;
    }

    function toggleVisible() {
      const el = inputRef.current;
      savedSelectionRef.current = el
        ? { start: el.selectionStart ?? el.value.length, end: el.selectionEnd ?? el.value.length }
        : null;
      setVisible((v) => !v);
    }

    function handleKeyEvent(e: KeyboardEvent<HTMLInputElement>) {
      if (typeof e.getModifierState === 'function') {
        setCapsLockOn(e.getModifierState('CapsLock'));
      }
    }

    const errorMessage = typeof error === 'string' ? error : null;
    const hasError = !!error;
    const requirements = showValidation
      ? PASSWORD_REQUIREMENTS.map((r) => ({ ...r, met: r.test(value) }))
      : [];
    const strength = showStrength ? getPasswordStrength(value) : 'empty';

    return (
      <div className="w-full">
        <motion.div animate={shakeControls} className="relative">
          <input
            ref={mergeRefs}
            id={id}
            type={visible ? 'text' : 'password'}
            value={value}
            onChange={onChange}
            onKeyDown={(e) => {
              handleKeyEvent(e);
              onKeyDownProp?.(e);
            }}
            onKeyUp={(e) => {
              handleKeyEvent(e);
              onKeyUpProp?.(e);
            }}
            disabled={disabled}
            aria-invalid={hasError || undefined}
            aria-describedby={errorMessage ? `${id}-error` : undefined}
            className={cn(
              'flex h-10 w-full rounded-lg border bg-card px-3 py-2 text-sm text-foreground shadow-xs transition-colors',
              'placeholder:text-muted-foreground',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:border-transparent',
              'disabled:cursor-not-allowed disabled:opacity-50',
              // Fixed regardless of allowToggle so the input's width can never
              // shift, whether or not a toggle button is even rendered.
              'pr-11',
              hasError ? 'border-danger focus-visible:ring-2 focus-visible:ring-danger' : 'border-input',
              className,
            )}
            {...restProps}
          />
          {allowToggle && (
            <button
              type="button"
              // preventDefault on mousedown stops the browser's default
              // focus-shift to this button (mousedown fires before click,
              // and a native focus change there is what triggers Chromium's
              // selection reset) — click and keyboard Enter/Space activation
              // (which never goes through mousedown) are both unaffected.
              onMouseDown={(e) => e.preventDefault()}
              onClick={toggleVisible}
              disabled={disabled}
              aria-label={visible ? 'Hide password' : 'Show password'}
              aria-pressed={visible}
              className={cn(
                'absolute inset-y-0 right-0 flex items-center justify-center',
                // 44x44 minimum touch target, overlapping the input's own
                // padding rather than shrinking the typing area for it.
                'h-11 w-11 text-muted-foreground transition-colors',
                'hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:rounded-lg',
                'disabled:cursor-not-allowed disabled:opacity-50',
              )}
            >
              <AnimatePresence mode="wait" initial={false}>
                <motion.span
                  key={visible ? 'visible' : 'hidden'}
                  initial={{ opacity: 0, scale: 0.8 }}
                  animate={{ opacity: 1, scale: 1 }}
                  exit={{ opacity: 0, scale: 0.8 }}
                  transition={{ duration: 0.15 }}
                  className="flex"
                >
                  {visible ? <EyeOff className="h-4 w-4" aria-hidden="true" /> : <Eye className="h-4 w-4" aria-hidden="true" />}
                </motion.span>
              </AnimatePresence>
            </button>
          )}
        </motion.div>

        <AnimatePresence initial={false}>
          {capsLockOn && (
            <motion.div
              initial={{ opacity: 0, height: 0 }}
              animate={{ opacity: 1, height: 'auto' }}
              exit={{ opacity: 0, height: 0 }}
              transition={{ duration: 0.15 }}
              className="mt-1.5 flex items-center gap-1.5 overflow-hidden text-xs font-medium text-warning"
            >
              <TriangleAlert className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              Caps Lock is ON
            </motion.div>
          )}
        </AnimatePresence>

        {errorMessage && (
          <p id={`${id}-error`} role="alert" className="mt-1.5 text-xs font-medium text-danger">
            {errorMessage}
          </p>
        )}

        {showStrength && value && (
          <div className="mt-2">
            <div className="flex gap-1" aria-hidden="true">
              {STRENGTH_LEVELS.map((level, i) => (
                <div
                  key={level}
                  className={cn(
                    'h-1 flex-1 rounded-full transition-colors duration-200',
                    i <= STRENGTH_LEVELS.indexOf(strength) ? STRENGTH_CONFIG[strength].barClass : 'bg-muted',
                  )}
                />
              ))}
            </div>
            <p className="mt-1 text-xs font-medium text-muted-foreground">
              Password strength: {STRENGTH_CONFIG[strength].label}
            </p>
          </div>
        )}

        {showValidation && value && (
          <ul className="mt-2 space-y-1">
            {requirements.map((r) => (
              <li
                key={r.id}
                className={cn(
                  'flex items-center gap-1.5 text-xs transition-colors duration-200',
                  r.met ? 'text-success' : 'text-muted-foreground',
                )}
              >
                {r.met ? (
                  <Check className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                ) : (
                  <X className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                )}
                {r.label}
              </li>
            ))}
          </ul>
        )}
      </div>
    );
  },
);
PasswordInput.displayName = 'PasswordInput';
