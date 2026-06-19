/**
 * Shared types for the AI Copilot. Mirrors the Spring DTOs in
 * `api/dto/CopilotDtos.java` and the SSE stream contract of `CopilotController`.
 */

export type CopilotRole = 'USER' | 'ASSISTANT' | 'SYSTEM';

export interface CopilotMessage {
  id?: string;
  role: CopilotRole;
  content: string;
  action?: string | null;
  createdAt?: string;
  /** Client-only: assistant turn still being streamed. */
  streaming?: boolean;
}

export interface ConversationSummary {
  id: string;
  page: string | null;
  title: string | null;
  createdAt: string;
  updatedAt: string;
}

/** Body for POST /api/copilot/stream. */
export interface CopilotStreamRequest {
  conversationId?: string | null;
  page?: string | null;
  action?: string | null;
  message?: string | null;
  contextId?: string | null;
}

/** App surfaces the Copilot is aware of (matches the backend page keys). */
export type CopilotPageKey = 'resume' | 'jobs' | 'applications' | 'workflow' | 'dashboard';
