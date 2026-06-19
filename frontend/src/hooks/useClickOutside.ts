import { useEffect, type RefObject } from 'react';

/**
 * Invokes `handler` when a pointer/focus event occurs outside `ref`.
 * Used to dismiss dropdown popovers. Listens on mousedown + focusin so the
 * popover also closes on keyboard tab-out, supporting accessible navigation.
 */
export function useClickOutside<T extends HTMLElement>(
  ref: RefObject<T>,
  handler: () => void,
  enabled = true,
): void {
  useEffect(() => {
    if (!enabled) return;

    function onEvent(event: Event) {
      const el = ref.current;
      if (el && !el.contains(event.target as Node)) handler();
    }

    document.addEventListener('mousedown', onEvent);
    document.addEventListener('focusin', onEvent);
    return () => {
      document.removeEventListener('mousedown', onEvent);
      document.removeEventListener('focusin', onEvent);
    };
  }, [ref, handler, enabled]);
}
