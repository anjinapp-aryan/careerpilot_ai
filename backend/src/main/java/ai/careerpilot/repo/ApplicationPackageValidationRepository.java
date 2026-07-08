package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationPackageValidation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationPackageValidationRepository extends JpaRepository<ApplicationPackageValidation, UUID> {

    List<ApplicationPackageValidation> findByApplicationPackageIdOrderByCreatedAtDesc(UUID applicationPackageId);

    Optional<ApplicationPackageValidation> findFirstByApplicationPackageIdOrderByCreatedAtDesc(UUID applicationPackageId);

    List<ApplicationPackageValidation> findByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByStatus(String status);
}
