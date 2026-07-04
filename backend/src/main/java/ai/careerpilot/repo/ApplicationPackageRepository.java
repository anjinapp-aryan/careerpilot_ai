package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationPackage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApplicationPackageRepository extends JpaRepository<ApplicationPackage, UUID> {

    Optional<ApplicationPackage> findByUserIdAndJobId(UUID userId, UUID jobId);
}
