package ai.careerpilot.repo;

import ai.careerpilot.domain.CandidatePreferences;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CandidatePreferencesRepository extends JpaRepository<CandidatePreferences, UUID> {
}
