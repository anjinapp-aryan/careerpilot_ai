package ai.careerpilot.resumetailoring.ats.dto;

import ai.careerpilot.domain.AtsOptimizationJob;
import ai.careerpilot.domain.ResumeAtsAnalysis;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class AtsOptimizationDtos {

    private AtsOptimizationDtos() {}

    /** Manual trigger body for {@code POST /api/resume/ats/analyze}. */
    public record AnalyzeRequest(String jobId) {}

    /** One ATS analysis of a tailored resume version. */
    public record AtsAnalysisResponse(
            UUID id,
            UUID jobId,
            UUID resumeTailoringId,
            Integer atsScore,
            List<String> matchedKeywords,
            List<String> missingKeywords,
            List<String> suggestions,
            String status,
            Instant createdAt) {

        public static AtsAnalysisResponse from(ResumeAtsAnalysis a) {
            return new AtsAnalysisResponse(a.getId(), a.getJobId(), a.getResumeTailoringId(), a.getAtsScore(),
                    csv(a.getMatchedKeywords()), csv(a.getMissingKeywords()), csv(a.getSuggestions()),
                    a.getStatus(), a.getCreatedAt());
        }

        private static List<String> csv(String v) {
            if (v == null || v.isBlank()) return List.of();
            return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }

    public record AtsAnalysisHistoryResponse(List<AtsAnalysisResponse> analyses) {
        public static AtsAnalysisHistoryResponse from(List<ResumeAtsAnalysis> rows) {
            return new AtsAnalysisHistoryResponse(rows.stream().map(AtsAnalysisResponse::from).toList());
        }
    }

    /** Returned immediately (HTTP 202) by {@code POST /api/resume/ats/analyze}, and by the polling endpoint. */
    public record AtsOptimizationJobResponse(
            UUID jobId,
            UUID targetJobId,
            String status,
            String errorReason,
            Instant createdAt,
            String pollUrl,
            AtsAnalysisResponse result) {

        public static AtsOptimizationJobResponse queued(AtsOptimizationJob job) {
            return new AtsOptimizationJobResponse(job.getId(), job.getJobId(), job.getStatus(), job.getErrorReason(),
                    job.getCreatedAt(), "/api/resume/ats/jobs/" + job.getId(), null);
        }

        public static AtsOptimizationJobResponse from(AtsOptimizationJob job, AtsAnalysisResponse result) {
            return new AtsOptimizationJobResponse(job.getId(), job.getJobId(), job.getStatus(), job.getErrorReason(),
                    job.getCreatedAt(), "/api/resume/ats/jobs/" + job.getId(), result);
        }
    }
}
