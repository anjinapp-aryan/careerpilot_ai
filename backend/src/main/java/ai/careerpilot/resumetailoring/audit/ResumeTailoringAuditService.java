package ai.careerpilot.resumetailoring.audit;

import ai.careerpilot.domain.ResumeTailoringAuditEntry;
import ai.careerpilot.repo.ResumeTailoringAuditRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Append-only audit trail for tailoring attempts (Step 10), including ones that never produced a
 * usable {@code ResumeTailoring} row (validation rejection, cache hit, error) — mirrors the
 * {@code recommendation_audit} / {@code recommendation_feedback} logging convention.
 */
@Service
public class ResumeTailoringAuditService {

    private final ResumeTailoringAuditRepository audit;

    public ResumeTailoringAuditService(ResumeTailoringAuditRepository audit) {
        this.audit = audit;
    }

    public void record(UUID userId, UUID jobId, UUID resumeTailoringId, Integer tailoringVersion,
                       UUID candidateProfileVersion, UUID recommendationAuditId, Integer improvementScore,
                       String outcome, String reason) {
        audit.save(ResumeTailoringAuditEntry.builder()
                .userId(userId).jobId(jobId)
                .resumeTailoringId(resumeTailoringId)
                .tailoringVersion(tailoringVersion)
                .candidateProfileVersion(candidateProfileVersion)
                .recommendationAuditId(recommendationAuditId)
                .improvementScore(improvementScore)
                .outcome(outcome)
                .reason(reason)
                .build());
    }

    public List<ResumeTailoringAuditEntry> history(UUID userId) {
        return audit.findByUserIdOrderByCreatedAtDesc(userId);
    }
}
