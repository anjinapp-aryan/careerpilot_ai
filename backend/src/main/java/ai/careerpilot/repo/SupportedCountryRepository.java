package ai.careerpilot.repo;

import ai.careerpilot.domain.SupportedCountry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupportedCountryRepository extends JpaRepository<SupportedCountry, UUID> {

    List<SupportedCountry> findByActiveTrue();

    Optional<SupportedCountry> findByCountryCodeIgnoreCase(String countryCode);

    Optional<SupportedCountry> findByDisplayNameIgnoreCase(String displayName);
}
