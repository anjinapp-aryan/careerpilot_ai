package ai.careerpilot.repo;

import ai.careerpilot.domain.ResumeAnalysisRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ResumeAnalysisRunRepository extends JpaRepository<ResumeAnalysisRun, UUID> {

    List<ResumeAnalysisRun> findByUserIdAndResumeIdOrderByCreatedAtDesc(UUID userId, UUID resumeId);

    List<ResumeAnalysisRun> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
