package ai.careerpilot.repo;

import ai.careerpilot.domain.UserCareerPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserCareerPreferenceRepository extends JpaRepository<UserCareerPreference, UUID> {

    Optional<UserCareerPreference> findByUserId(UUID userId);
}
