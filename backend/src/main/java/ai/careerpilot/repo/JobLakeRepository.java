package ai.careerpilot.repo;

import ai.careerpilot.domain.JobLake;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JobLakeRepository extends JpaRepository<JobLake, UUID> {

    Optional<JobLake> findByNormalizedJobId(UUID normalizedJobId);

    long countByStatus(String status);
}
