package ai.careerpilot.repo;

import ai.careerpilot.domain.JobDuplicateMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JobDuplicateMappingRepository extends JpaRepository<JobDuplicateMapping, UUID> {

    boolean existsBySourceJob(UUID sourceJob);

    Optional<JobDuplicateMapping> findBySourceJob(UUID sourceJob);

    long countByCanonicalJob(UUID canonicalJob);
}
