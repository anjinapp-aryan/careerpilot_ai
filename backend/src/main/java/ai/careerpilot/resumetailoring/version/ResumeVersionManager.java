package ai.careerpilot.resumetailoring.version;

import ai.careerpilot.repo.ResumeTailoringRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Assigns the next {@code tailoring_version} integer for a (user, job) pair. Scoped per-job (not
 * per-user globally) so re-tailoring the same job after a profile/job change produces v2, v3, ...
 * while a different job for the same user starts again at v1 — rendered as "v1.N" at the API/DTO
 * layer (see the module Javadoc on {@code ResumeTailoring} for the versioning decision).
 */
@Component
public class ResumeVersionManager {

    private final ResumeTailoringRepository tailorings;

    public ResumeVersionManager(ResumeTailoringRepository tailorings) {
        this.tailorings = tailorings;
    }

    public int nextVersion(UUID userId, UUID jobId) {
        return (int) tailorings.countByUserIdAndJobId(userId, jobId) + 1;
    }

    /** Renders an integer {@code tailoring_version} as the user-facing "v1.N" string. */
    public static String render(int tailoringVersion) {
        return "v1." + tailoringVersion;
    }
}
