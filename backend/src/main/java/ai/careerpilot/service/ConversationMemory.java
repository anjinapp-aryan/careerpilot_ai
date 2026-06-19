package ai.careerpilot.service;

import ai.careerpilot.domain.CopilotConversation;
import ai.careerpilot.domain.CopilotMessage;
import ai.careerpilot.repo.CopilotConversationRepository;
import ai.careerpilot.repo.CopilotMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Durable conversation memory for the AI Copilot. Owns persistence and recall
 * of {@link CopilotConversation} / {@link CopilotMessage} rows in Neon Postgres,
 * enforcing per-user ownership (multi-tenant isolation is checked manually, in
 * line with the rest of the codebase — there is no row-level security).
 */
@Service
public class ConversationMemory {

    /** How many recent turns to replay back into the model as context. */
    public static final int CONTEXT_WINDOW = 12;

    private final CopilotConversationRepository conversations;
    private final CopilotMessageRepository messages;

    public ConversationMemory(CopilotConversationRepository conversations,
                              CopilotMessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    /**
     * Resolves the conversation for a turn: loads + ownership-checks an existing
     * one, or creates a fresh conversation anchored to the current page.
     */
    @Transactional
    public CopilotConversation resolve(UUID userId, UUID orgId, UUID conversationId, String page) {
        if (conversationId != null) {
            CopilotConversation c = conversations.findById(conversationId)
                    .orElseThrow(() -> new IllegalArgumentException("Conversation not found"));
            requireOwner(c, userId);
            return c;
        }
        CopilotConversation c = CopilotConversation.builder()
                .userId(userId)
                .orgId(orgId)
                .page(page)
                .title("New conversation")
                .build();
        return conversations.save(c);
    }

    /** The recent turns (oldest → newest) used to build the model's context window. */
    @Transactional(readOnly = true)
    public List<CopilotMessage> recentHistory(UUID conversationId) {
        List<CopilotMessage> all = messages.findByConversationIdOrderByCreatedAtAsc(conversationId);
        int from = Math.max(0, all.size() - CONTEXT_WINDOW);
        return all.subList(from, all.size());
    }

    /** Appends a message and, for the first user turn, derives the conversation title. */
    @Transactional
    public CopilotMessage append(UUID conversationId, String role, String content, String action) {
        CopilotMessage m = messages.save(CopilotMessage.builder()
                .conversationId(conversationId)
                .role(role)
                .content(content)
                .action(action)
                .build());
        conversations.findById(conversationId).ifPresent(c -> {
            if ("USER".equals(role) && ("New conversation".equals(c.getTitle()) || c.getTitle() == null)) {
                c.setTitle(deriveTitle(content));
            }
            c.setUpdatedAt(java.time.Instant.now()); // bump so it sorts to the top of the list
        });
        return m;
    }

    @Transactional(readOnly = true)
    public List<CopilotConversation> listForUser(UUID userId) {
        return conversations.findTop50ByUserIdOrderByUpdatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<CopilotMessage> messagesFor(UUID conversationId, UUID userId) {
        CopilotConversation c = conversations.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found"));
        requireOwner(c, userId);
        return messages.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    @Transactional
    public void delete(UUID conversationId, UUID userId) {
        CopilotConversation c = conversations.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found"));
        requireOwner(c, userId);
        conversations.delete(c); // messages cascade via FK ON DELETE CASCADE
    }

    private void requireOwner(CopilotConversation c, UUID userId) {
        if (!c.getUserId().equals(userId)) {
            throw new SecurityException("forbidden");
        }
    }

    private String deriveTitle(String content) {
        String t = content.strip().replaceAll("\\s+", " ");
        if (t.length() > 60) t = t.substring(0, 57) + "…";
        return t.isBlank() ? "New conversation" : t;
    }
}
