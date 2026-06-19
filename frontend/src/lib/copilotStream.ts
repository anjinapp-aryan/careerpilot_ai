import { useAuthStore } from '@/lib/auth';
import type { CopilotStreamRequest } from '@/types/copilot';

const BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

export interface StreamHandlers {
  /** Fired once with the (possibly newly created) conversation id. */
  onMeta?: (conversationId: string) => void;
  /** Fired for each streamed text chunk. */
  onDelta: (text: string) => void;
  /** Fired when the stream completes successfully. */
  onDone?: (conversationId: string) => void;
  /** Fired on a server-signalled error event. */
  onError?: (message: string) => void;
}

/**
 * Streams a Copilot turn over Server-Sent Events using `fetch` (not the native
 * `EventSource`, which cannot attach the `Authorization` header the existing
 * JwtAuthFilter requires). Parses the SSE frames and dispatches typed events.
 *
 * Pass an `AbortSignal` to cancel an in-flight stream (e.g. on unmount or a new
 * send). Returns when the stream ends.
 */
export async function streamCopilot(
  req: CopilotStreamRequest,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const token = useAuthStore.getState().token;

  const res = await fetch(`${BASE_URL}/api/copilot/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(req),
    signal,
  });

  if (res.status === 401) {
    // Session expired/invalid — recover the same way the axios client does.
    if (useAuthStore.getState().token) useAuthStore.getState().expireSession();
    throw new Error('Your session expired. Please sign in again.');
  }
  if (!res.ok || !res.body) {
    throw new Error(`Copilot request failed (${res.status})`);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      // SSE frames are separated by a blank line.
      let sep: number;
      while ((sep = buffer.indexOf('\n\n')) !== -1) {
        const frame = buffer.slice(0, sep);
        buffer = buffer.slice(sep + 2);
        dispatchFrame(frame, handlers);
      }
    }
  } finally {
    reader.releaseLock?.();
  }
}

function dispatchFrame(frame: string, handlers: StreamHandlers): void {
  let event = 'message';
  const dataLines: string[] = [];

  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
  }

  if (dataLines.length === 0) return;
  let data: any = {};
  try {
    data = JSON.parse(dataLines.join('\n'));
  } catch {
    return;
  }

  switch (event) {
    case 'meta':
      if (data.conversationId) handlers.onMeta?.(data.conversationId);
      break;
    case 'delta':
      if (typeof data.text === 'string') handlers.onDelta(data.text);
      break;
    case 'done':
      handlers.onDone?.(data.conversationId);
      break;
    case 'error':
      handlers.onError?.(data.message ?? 'The assistant failed to respond.');
      break;
  }
}
