package ai.careerpilot.repo;

import ai.careerpilot.domain.AutoApplyPackage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AutoApplyPackageRepository extends JpaRepository<AutoApplyPackage, UUID> {

    Optional<AutoApplyPackage> findFirstByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);

    long countByStatus(String status);
}
