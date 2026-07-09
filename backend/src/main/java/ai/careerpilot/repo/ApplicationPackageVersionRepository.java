package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationPackageVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationPackageVersionRepository extends JpaRepository<ApplicationPackageVersion, UUID> {

    List<ApplicationPackageVersion> findByApplicationPackageIdOrderByPackageVersionDesc(UUID applicationPackageId);

    Optional<ApplicationPackageVersion> findByApplicationPackageIdAndPackageVersion(UUID applicationPackageId, Integer packageVersion);
}
