package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "copilot_conversations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CopilotConversation {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "org_id", nullable = false) private UUID orgId;

    /** Surface the conversation was started on: resume | jobs | applications | workflow | dashboard. */
    @Column private String page;
    @Column private String title;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
