package ai.careerpilot.resumetailoring.dto;

import ai.careerpilot.domain.ResumeTailoringJob;
import ai.careerpilot.resumetailoring.dto.ResumeTailoringDtos.TailoredResumeResponse;

import java.time.Instant;
import java.util.UUID;

public final class ResumeTailoringJobDtos {

    private ResumeTailoringJobDtos() {}

    /**
     * Returned immediately (HTTP 202) by {@code POST /api/resume/tailor} and
     * {@code POST /api/resume/tailored/rebuild}, and by {@code GET /api/resume/tailor/jobs/{id}}
     * while the job is still {@code QUEUED}/{@code RUNNING}. Once {@code SUCCEEDED}, {@code result}
     * is populated with the same shape the old synchronous endpoint used to return directly.
     */
    public record TailoringJobResponse(
            UUID jobId,
            UUID targetJobId,
            String status,
            String errorReason,
            Instant createdAt,
            String pollUrl,
            TailoredResumeResponse result) {

        public static TailoringJobResponse queued(ResumeTailoringJob job) {
            return new TailoringJobResponse(job.getId(), job.getJobId(), job.getStatus(), null,
                    job.getCreatedAt(), "/api/resume/tailor/jobs/" + job.getId(), null);
        }

        public static TailoringJobResponse from(ResumeTailoringJob job, TailoredResumeResponse result) {
            return new TailoringJobResponse(job.getId(), job.getJobId(), job.getStatus(),
                    job.getErrorReason(), job.getCreatedAt(), "/api/resume/tailor/jobs/" + job.getId(), result);
        }
    }
}
