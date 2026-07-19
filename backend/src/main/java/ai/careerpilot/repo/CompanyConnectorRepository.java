package ai.careerpilot.repo;

import ai.careerpilot.jobdiscovery.enterprise.CompanyConnector;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompanyConnectorRepository extends JpaRepository<CompanyConnector, UUID> {
    List<CompanyConnector> findByAtsType(String atsType);
    List<CompanyConnector> findByAtsTypeOrderByPriorityDesc(String atsType);
    List<CompanyConnector> findByEnabledTrue();
    Optional<CompanyConnector> findByAtsTypeAndCompanyName(String atsType, String companyName);
    long countByAtsType(String atsType);
    long countByEnabledTrue();
    long countByHealthStatus(String healthStatus);

    // Gap A — Company Discovery Agent (additive).
    List<CompanyConnector> findByDiscoveryStatus(String discoveryStatus);
    long countByDiscoveryStatus(String discoveryStatus);
}
