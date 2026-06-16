package ai.careerpilot.repo;

import ai.careerpilot.domain.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    @Query("select j from Job j where (:q is null or lower(j.title) like lower(concat('%',:q,'%')) " +
           "or lower(j.company) like lower(concat('%',:q,'%')))")
    Page<Job> search(String q, Pageable pageable);
}
