package ai.careerpilot.api.dto;

import ai.careerpilot.domain.ResumeVersion;

import java.time.Instant;
import java.util.UUID;

/** Request/response shapes for Resume Optimization version history. */
public final class ResumeVersionDtos {

    private ResumeVersionDtos() {}

    /** A single optimized resume version. {@code optimizedText} is omitted from list views. */
    public record ResumeVersionResponse(
            UUID id,
            UUID resumeId,
            int versionNumber,
            String optimizationMode,
            Integer atsBefore,
            Integer atsAfter,
            String providerUsed,
            String workflowThreadId,
            boolean hasDownload,
            Instant createdAt) {

        public static ResumeVersionResponse of(ResumeVersion v) {
            return new ResumeVersionResponse(
                    v.getId(),
                    v.getResumeId(),
                    v.getVersionNumber() == null ? 0 : v.getVersionNumber(),
                    v.getOptimizationMode(),
                    v.getAtsBefore(),
                    v.getAtsAfter(),
                    v.getProviderUsed(),
                    v.getWorkflowThreadId(),
                    v.getS3Key() != null && !v.getS3Key().isBlank(),
                    v.getCreatedAt());
        }
    }
}
