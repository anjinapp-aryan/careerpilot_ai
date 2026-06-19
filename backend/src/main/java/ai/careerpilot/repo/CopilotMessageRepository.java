package ai.careerpilot.repo;

import ai.careerpilot.domain.CopilotMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CopilotMessageRepository extends JpaRepository<CopilotMessage, UUID> {
    List<CopilotMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);
}
