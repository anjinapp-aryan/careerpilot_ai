package ai.careerpilot.repo;

import ai.careerpilot.domain.JobNormalized;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobNormalizedRepository extends JpaRepository<JobNormalized, UUID> {

    Optional<JobNormalized> findByProviderAndExternalJobId(String provider, String externalJobId);

    /**
     * Candidate duplicates: normalized jobs in the same (country, city) bucket — null-safe so
     * location-less rows still cluster. Used by the lake deduplicator before text confirmation.
     */
    @Query("select j from JobNormalized j where " +
           "((:country is null and j.country is null) or j.country = :country) and " +
           "((:city is null and j.city is null) or j.city = :city)")
    List<JobNormalized> findDedupCandidates(@Param("country") String country, @Param("city") String city);
}
