import { useEffect, useMemo, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { AnimatePresence, motion } from 'framer-motion';
import {
  ArrowUp,
  History,
  PanelRightClose,
  PanelRightOpen,
  Plus,
  User as UserIcon,
  X,
} from 'lucide-react';
import { api } from '@/lib/api';
import { streamCopilot } from '@/lib/copilotStream';
import { useCopilot } from '@/hooks/useCopilot';
import { useIsDesktop } from '@/hooks/useMediaQuery';
import { pageConfigForPath, type CopilotAction } from './copilotActions';
import { Markdown } from './Markdown';
import { CopilotAvatar, type CopilotState } from './CopilotAvatar';
import { CopilotHeader } from './CopilotHeader';
import { Tooltip } from '@/components/ui/tooltip';
import { cn } from '@/lib/cn';
import type { ConversationSummary, CopilotMessage } from '@/types/copilot';

const EXPANDED = 420;
const COLLAPSED = 60;

export function CopilotPanel() {
  const { pathname } = useLocation();
  const pageConfig = useMemo(() => pageConfigForPath(pathname), [pathname]);
  const qc = useQueryClient();
  const isDesktop = useIsDesktop();

  const collapsed = useCopilot((s) => s.collapsed);
  const toggleCollapsed = useCopilot((s) => s.toggleCollapsed);
  const mobileOpen = useCopilot((s) => s.mobileOpen);
  const setMobileOpen = useCopilot((s) => s.setMobileOpen);

  const [messages, setMessages] = useState<CopilotMessage[]>([]);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [avatarState, setAvatarState] = useState<CopilotState>('ready');

  const abortRef = useRef<AbortController | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const revertRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  /** Set the avatar state, optionally auto-reverting to `ready` after a beat. */
  function flashAvatar(state: CopilotState, revertMs?: number) {
    if (revertRef.current) clearTimeout(revertRef.current);
    setAvatarState(state);
    if (revertMs) revertRef.current = setTimeout(() => setAvatarState('ready'), revertMs);
  }

  const historyQuery = useQuery<ConversationSummary[]>({
    queryKey: ['copilot-conversations'],
    queryFn: async () => (await api.get('/api/copilot/conversations')).data,
    enabled: showHistory,
  });

  // Auto-scroll to the newest content as it streams in.
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages]);

  // Cancel any in-flight stream + timers on unmount.
  useEffect(
    () => () => {
      abortRef.current?.abort();
      if (revertRef.current) clearTimeout(revertRef.current);
    },
    [],
  );

  async function send(text: string, action?: string) {
    const message = text.trim();
    if (!message || streaming) return;

    setMessages((m) => [
      ...m,
      { role: 'USER', content: message, action },
      { role: 'ASSISTANT', content: '', streaming: true },
    ]);
    setInput('');
    setStreaming(true);
    flashAvatar(pageConfig.page === 'workflow' ? 'processing' : 'thinking');

    const ctrl = new AbortController();
    abortRef.current = ctrl;

    try {
      await streamCopilot(
        {
          conversationId,
          page: pageConfig.page,
          action: action ?? null,
          message,
          contextId: null,
        },
        {
          onMeta: (cid) => setConversationId(cid),
          onDelta: (t) => setMessages((m) => appendToLast(m, t)),
          onError: (msg) => {
            setMessages((m) => setLastContent(m, msg));
            flashAvatar('error', 2600);
          },
          onDone: () => {
            qc.invalidateQueries({ queryKey: ['copilot-conversations'] });
            flashAvatar('success', 1800);
          },
        },
        ctrl.signal,
      );
    } catch (e: any) {
      if (e?.name !== 'AbortError') {
        setMessages((m) => setLastContent(m, e?.message || 'Something went wrong. Please try again.'));
        flashAvatar('error', 2600);
      }
    } finally {
      setStreaming(false);
      setMessages((m) => markLastDone(m));
      abortRef.current = null;
      // Safety net: if neither success nor error fired, settle back to ready.
      setAvatarState((s) => (s === 'thinking' || s === 'processing' ? 'ready' : s));
    }
  }

  function newChat() {
    abortRef.current?.abort();
    setMessages([]);
    setConversationId(null);
    setStreaming(false);
    setShowHistory(false);
  }

  async function openConversation(id: string) {
    abortRef.current?.abort();
    setStreaming(false);
    setShowHistory(false);
    try {
      const { data } = await api.get<any[]>(`/api/copilot/conversations/${id}/messages`);
      setMessages(
        data.map((d) => ({
          id: d.id,
          role: d.role,
          content: d.content,
          action: d.action,
          createdAt: d.createdAt,
        })),
      );
      setConversationId(id);
    } catch {
      /* ignore — stays on current conversation */
    }
  }

  const body = (
    <div className="flex h-full flex-col bg-card">
      <PanelHeader
        state={avatarState}
        onNewChat={newChat}
        onToggleHistory={() => setShowHistory((v) => !v)}
        onCollapse={isDesktop ? toggleCollapsed : () => setMobileOpen(false)}
        collapseIcon={isDesktop ? 'collapse' : 'close'}
      />

      <div className="relative flex-1 overflow-hidden">
        <AnimatePresence>
          {showHistory && (
            <HistoryOverlay
              conversations={historyQuery.data ?? []}
              loading={historyQuery.isLoading}
              activeId={conversationId}
              onPick={openConversation}
              onClose={() => setShowHistory(false)}
            />
          )}
        </AnimatePresence>

        <div ref={scrollRef} className="h-full overflow-y-auto px-4 py-4">
          {messages.length === 0 ? (
            <Welcome pageLabel={pageConfig.label} actions={pageConfig.actions} onAction={send} />
          ) : (
            <div className="space-y-4">
              {messages.map((m, i) => (
                <MessageBubble key={m.id ?? i} message={m} />
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Context-aware quick actions */}
      {pageConfig.actions.length > 0 && messages.length > 0 && (
        <div className="flex flex-wrap gap-1.5 border-t border-border px-3 py-2">
          {pageConfig.actions.map((a) => (
            <QuickActionChip key={a.key} action={a} disabled={streaming} onClick={() => send(a.prompt, a.key)} />
          ))}
        </div>
      )}

      <Composer
        value={input}
        onChange={setInput}
        onSend={() => send(input)}
        streaming={streaming}
        pageLabel={pageConfig.label}
      />
    </div>
  );

  // --- Mobile: floating trigger + slide-over drawer ---------------------
  if (!isDesktop) {
    return (
      <>
        <button
          type="button"
          onClick={() => setMobileOpen(true)}
          aria-label="Open CareerPilot AI Copilot"
          className="fixed bottom-5 right-5 z-40 flex h-14 w-14 items-center justify-center rounded-full border border-border bg-card shadow-lg shadow-primary/20 transition-transform hover:scale-105 lg:hidden"
        >
          <CopilotAvatar size={44} state={avatarState} />
        </button>
        <AnimatePresence>
          {mobileOpen && (
            <div className="fixed inset-0 z-50 lg:hidden">
              <motion.div
                className="absolute inset-0 bg-foreground/40 backdrop-blur-sm"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                onClick={() => setMobileOpen(false)}
              />
              <motion.div
                className="absolute inset-y-0 right-0 w-[min(420px,100vw)] border-l border-border shadow-xl"
                initial={{ x: '100%' }}
                animate={{ x: 0 }}
                exit={{ x: '100%' }}
                transition={{ duration: 0.25, ease: 'easeOut' }}
              >
                {body}
              </motion.div>
            </div>
          )}
        </AnimatePresence>
      </>
    );
  }

  // --- Desktop: in-flow collapsible rail --------------------------------
  return (
    <motion.aside
      initial={false}
      animate={{ width: collapsed ? COLLAPSED : EXPANDED }}
      transition={{ duration: 0.22, ease: 'easeOut' }}
      className="hidden shrink-0 border-l border-border lg:block"
      aria-label="AI Copilot"
    >
      {collapsed ? (
        <CollapsedRail state={avatarState} onExpand={toggleCollapsed} onNewChat={() => { toggleCollapsed(); newChat(); }} />
      ) : (
        body
      )}
    </motion.aside>
  );
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

function PanelHeader({
  state,
  onNewChat,
  onToggleHistory,
  onCollapse,
  collapseIcon,
}: {
  state: CopilotState;
  onNewChat: () => void;
  onToggleHistory: () => void;
  onCollapse: () => void;
  collapseIcon: 'collapse' | 'close';
}) {
  return (
    <CopilotHeader
      state={state}
      right={
        <>
          <IconButton label="History" onClick={onToggleHistory}><History className="h-[18px] w-[18px]" /></IconButton>
          <IconButton label="New chat" onClick={onNewChat}><Plus className="h-[18px] w-[18px]" /></IconButton>
          <IconButton label={collapseIcon === 'collapse' ? 'Collapse' : 'Close'} onClick={onCollapse}>
            {collapseIcon === 'collapse' ? <PanelRightClose className="h-[18px] w-[18px]" /> : <X className="h-[18px] w-[18px]" />}
          </IconButton>
        </>
      }
    />
  );
}

function CollapsedRail({
  state,
  onExpand,
  onNewChat,
}: {
  state: CopilotState;
  onExpand: () => void;
  onNewChat: () => void;
}) {
  return (
    <div className="flex h-full flex-col items-center gap-2 py-3">
      {/* The avatar IS the expand control (32px per brand spec). */}
      <Tooltip content="CareerPilot AI" side="left">
        <button
          onClick={onExpand}
          aria-label="Expand CareerPilot AI Copilot"
          className="flex items-center justify-center rounded-full transition-transform hover:scale-110 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <CopilotAvatar size={36} state={state} />
        </button>
      </Tooltip>
      <Tooltip content="New chat" side="left">
        <button
          onClick={onNewChat}
          aria-label="New chat"
          className="mt-1 flex h-9 w-9 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
        >
          <Plus className="h-[18px] w-[18px]" />
        </button>
      </Tooltip>
      <div className="flex-1" />
      <Tooltip content="Expand" side="left">
        <button
          onClick={onExpand}
          aria-label="Expand"
          className="flex h-9 w-9 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
        >
          <PanelRightOpen className="h-[18px] w-[18px]" />
        </button>
      </Tooltip>
    </div>
  );
}

function IconButton({ label, onClick, children }: { label: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <Tooltip content={label} side="bottom">
      <button
        type="button"
        onClick={onClick}
        aria-label={label}
        className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
      >
        {children}
      </button>
    </Tooltip>
  );
}

function Welcome({
  pageLabel,
  actions,
  onAction,
}: {
  pageLabel: string;
  actions: CopilotAction[];
  onAction: (prompt: string, action: string) => void;
}) {
  return (
    <div className="flex h-full flex-col items-center justify-center px-2 text-center">
      <CopilotAvatar size={64} state="ready" />
      <h3 className="mt-3 text-sm font-semibold text-foreground">How can I help?</h3>
      <p className="mt-1 max-w-[16rem] text-xs text-muted-foreground">
        I'm aware you're on <span className="font-medium text-foreground">{pageLabel}</span>. Ask me
        anything, or start with a suggestion.
      </p>
      {actions.length > 0 && (
        <div className="mt-5 w-full space-y-2">
          {actions.map((a) => {
            const Icon = a.icon;
            return (
              <button
                key={a.key}
                onClick={() => onAction(a.prompt, a.key)}
                className="flex w-full items-center gap-3 rounded-xl border border-border bg-muted/30 px-3 py-2.5 text-left text-sm font-medium text-foreground transition-colors hover:border-primary/40 hover:bg-primary/5"
              >
                <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                  <Icon className="h-4 w-4" />
                </span>
                {a.label}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

function QuickActionChip({
  action,
  disabled,
  onClick,
}: {
  action: CopilotAction;
  disabled: boolean;
  onClick: () => void;
}) {
  const Icon = action.icon;
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className="inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-2.5 py-1 text-xs font-medium text-foreground transition-colors hover:border-primary/40 hover:bg-primary/5 disabled:cursor-not-allowed disabled:opacity-50"
    >
      <Icon className="h-3.5 w-3.5 text-primary" />
      {action.label}
    </button>
  );
}

function MessageBubble({ message }: { message: CopilotMessage }) {
  const isUser = message.role === 'USER';
  return (
    <div className={cn('flex gap-2.5', isUser && 'flex-row-reverse')}>
      {isUser ? (
        <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground">
          <UserIcon className="h-4 w-4" />
        </span>
      ) : (
        <CopilotAvatar size={28} animated={false} className="mt-0.5" />
      )}
      <div
        className={cn(
          'min-w-0 max-w-[85%] rounded-2xl px-3.5 py-2.5',
          isUser ? 'bg-primary text-primary-foreground' : 'border border-border bg-muted/40',
        )}
      >
        {isUser ? (
          <p className="whitespace-pre-wrap text-sm leading-relaxed">{message.content}</p>
        ) : message.content ? (
          <Markdown content={message.content} />
        ) : (
          <TypingDots />
        )}
      </div>
    </div>
  );
}

function TypingDots() {
  return (
    <div className="flex items-center gap-1 py-1" aria-label="Assistant is typing">
      {[0, 1, 2].map((i) => (
        <motion.span
          key={i}
          className="h-1.5 w-1.5 rounded-full bg-muted-foreground"
          animate={{ opacity: [0.3, 1, 0.3] }}
          transition={{ duration: 1, repeat: Infinity, delay: i * 0.2 }}
        />
      ))}
    </div>
  );
}

function HistoryOverlay({
  conversations,
  loading,
  activeId,
  onPick,
  onClose,
}: {
  conversations: ConversationSummary[];
  loading: boolean;
  activeId: string | null;
  onPick: (id: string) => void;
  onClose: () => void;
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: -8 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -8 }}
      transition={{ duration: 0.15 }}
      className="absolute inset-0 z-10 flex flex-col bg-card"
    >
      <div className="flex items-center justify-between border-b border-border px-4 py-3">
        <p className="text-sm font-semibold text-foreground">Conversations</p>
        <button onClick={onClose} aria-label="Close history" className="rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground">
          <X className="h-4 w-4" />
        </button>
      </div>
      <div className="flex-1 overflow-y-auto p-2">
        {loading ? (
          <p className="px-3 py-6 text-center text-sm text-muted-foreground">Loading…</p>
        ) : conversations.length === 0 ? (
          <p className="px-3 py-6 text-center text-sm text-muted-foreground">No conversations yet.</p>
        ) : (
          conversations.map((c) => (
            <button
              key={c.id}
              onClick={() => onPick(c.id)}
              className={cn(
                'flex w-full flex-col gap-0.5 rounded-lg px-3 py-2 text-left transition-colors hover:bg-muted',
                activeId === c.id && 'bg-muted',
              )}
            >
              <span className="truncate text-sm font-medium text-foreground">{c.title || 'Untitled'}</span>
              <span className="text-[11px] capitalize text-muted-foreground">
                {(c.page ?? 'general')} · {new Date(c.updatedAt).toLocaleDateString()}
              </span>
            </button>
          ))
        )}
      </div>
    </motion.div>
  );
}

function Composer({
  value,
  onChange,
  onSend,
  streaming,
  pageLabel,
}: {
  value: string;
  onChange: (v: string) => void;
  onSend: () => void;
  streaming: boolean;
  pageLabel: string;
}) {
  return (
    <div className="border-t border-border p-3">
      <div className="flex items-end gap-2 rounded-xl border border-input bg-card p-1.5 focus-within:ring-2 focus-within:ring-ring">
        <textarea
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              onSend();
            }
          }}
          rows={1}
          placeholder={`Ask about ${pageLabel.toLowerCase()}…`}
          className="max-h-32 flex-1 resize-none bg-transparent px-2 py-1.5 text-sm text-foreground outline-none placeholder:text-muted-foreground"
        />
        <button
          type="button"
          onClick={onSend}
          disabled={streaming || !value.trim()}
          aria-label="Send"
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-primary text-primary-foreground transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-40"
        >
          <ArrowUp className="h-4 w-4" />
        </button>
      </div>
      <p className="mt-1.5 px-1 text-[10px] text-muted-foreground">
        Copilot can make mistakes. Verify important details.
      </p>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Message-list update helpers
// ---------------------------------------------------------------------------

function appendToLast(messages: CopilotMessage[], delta: string): CopilotMessage[] {
  if (messages.length === 0) return messages;
  const next = messages.slice();
  const last = next[next.length - 1];
  if (last.role === 'ASSISTANT') {
    next[next.length - 1] = { ...last, content: last.content + delta, streaming: true };
  }
  return next;
}

function setLastContent(messages: CopilotMessage[], content: string): CopilotMessage[] {
  if (messages.length === 0) return messages;
  const next = messages.slice();
  const last = next[next.length - 1];
  if (last.role === 'ASSISTANT') {
    next[next.length - 1] = { ...last, content: last.content || content, streaming: false };
  }
  return next;
}

function markLastDone(messages: CopilotMessage[]): CopilotMessage[] {
  if (messages.length === 0) return messages;
  const next = messages.slice();
  const last = next[next.length - 1];
  if (last.role === 'ASSISTANT' && last.streaming) {
    next[next.length - 1] = { ...last, streaming: false };
  }
  return next;
}
