package ai.careerpilot.repo;

import ai.careerpilot.domain.CountryIntelligence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CountryIntelligenceRepository extends JpaRepository<CountryIntelligence, UUID> {

    Optional<CountryIntelligence> findByCountryCodeIgnoreCase(String countryCode);
}
