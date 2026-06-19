package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "copilot_messages")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CopilotMessage {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "conversation_id", nullable = false) private UUID conversationId;

    /** USER | ASSISTANT | SYSTEM */
    @Column(nullable = false) private String role;

    @Column(nullable = false, columnDefinition = "text") private String content;

    /** The Copilot action that produced this turn, when triggered from a quick-action. */
    @Column private String action;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
