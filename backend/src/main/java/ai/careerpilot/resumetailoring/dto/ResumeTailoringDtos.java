package ai.careerpilot.resumetailoring.dto;

import ai.careerpilot.domain.ResumeTailoring;
import ai.careerpilot.resumetailoring.version.ResumeVersionManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ResumeTailoringDtos {

    private ResumeTailoringDtos() {}

    /** Manual trigger body for {@code POST /api/resume/tailor}. */
    public record TailorRequest(String jobId) {}

    /** One tailored-resume version, rendered for the API/UI (version rendered as "v1.N"). */
    public record TailoredResumeResponse(
            UUID id,
            UUID jobId,
            String version,
            String tailoredResumeText,
            Integer atsBefore,
            Integer atsAfter,
            Integer improvementScore,
            String status,
            Instant createdAt) {

        public static TailoredResumeResponse from(ResumeTailoring t) {
            return new TailoredResumeResponse(
                    t.getId(), t.getJobId(), ResumeVersionManager.render(t.getTailoringVersion()),
                    t.getTailoredResumeText(), t.getAtsBefore(), t.getAtsAfter(),
                    t.getImprovementScore(), t.getStatus(), t.getCreatedAt());
        }
    }

    public record TailoringHistoryResponse(List<TailoredResumeResponse> versions) {
        public static TailoringHistoryResponse from(List<ResumeTailoring> rows) {
            return new TailoringHistoryResponse(rows.stream().map(TailoredResumeResponse::from).toList());
        }
    }
}
