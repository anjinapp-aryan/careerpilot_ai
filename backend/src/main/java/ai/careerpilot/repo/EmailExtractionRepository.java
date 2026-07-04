package ai.careerpilot.repo;

import ai.careerpilot.domain.EmailExtraction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EmailExtractionRepository extends JpaRepository<EmailExtraction, UUID> {

    List<EmailExtraction> findByEmailId(UUID emailId);
}
