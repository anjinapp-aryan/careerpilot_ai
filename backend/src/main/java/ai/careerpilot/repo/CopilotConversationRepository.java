package ai.careerpilot.repo;

import ai.careerpilot.domain.CopilotConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CopilotConversationRepository extends JpaRepository<CopilotConversation, UUID> {
    List<CopilotConversation> findTop50ByUserIdOrderByUpdatedAtDesc(UUID userId);
}
