package ai.careerpilot.repo;

import ai.careerpilot.domain.CoverLetterVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CoverLetterVersionRepository extends JpaRepository<CoverLetterVersion, UUID> {

    List<CoverLetterVersion> findByCoverLetterIdOrderByVersionDesc(UUID coverLetterId);
}
