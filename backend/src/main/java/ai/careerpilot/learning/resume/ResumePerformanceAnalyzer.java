package ai.careerpilot.learning.resume;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.ResumeAtsAnalysis;
import ai.careerpilot.learning.LearningEventType;
import ai.careerpilot.repo.LearningEventRepository;
import ai.careerpilot.repo.ResumeAtsAnalysisRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 6.5 — per-resume-version performance stats. Resume version is not directly recorded on
 * application/interview/offer events (they only carry {@code jobId}), so this analyzer first
 * resolves job → resume version from that user's {@code RESUME_SELECTED} events, then attributes
 * every other event for the same job to that version.
 */
@Component
public class ResumePerformanceAnalyzer {

    public record VersionStats(String resumeVersion, int applications, int interviews, int offers,
                               BigDecimal atsScoreAvg, BigDecimal interviewRate, BigDecimal offerRate) {}

    private final LearningEventRepository events;
    private final ResumeAtsAnalysisRepository atsAnalyses;

    public ResumePerformanceAnalyzer(LearningEventRepository events, ResumeAtsAnalysisRepository atsAnalyses) {
        this.events = events;
        this.atsAnalyses = atsAnalyses;
    }

    public List<VersionStats> analyze(UUID userId) {
        List<LearningEvent> history = events.findByUserIdOrderByCreatedAtDesc(userId);

        Map<UUID, String> jobToVersion = new LinkedHashMap<>();
        for (LearningEvent e : history) {
            if (LearningEventType.RESUME_SELECTED.name().equals(e.getEventType())
                    && e.getJobId() != null && e.getResumeVersion() != null) {
                jobToVersion.putIfAbsent(e.getJobId(), e.getResumeVersion());
            }
        }

        Map<String, int[]> counts = new LinkedHashMap<>(); // [applications, interviews, offers]
        for (LearningEvent e : history) {
            if (e.getJobId() == null) continue;
            String version = jobToVersion.get(e.getJobId());
            if (version == null) continue;
            int[] c = counts.computeIfAbsent(version, k -> new int[3]);
            if (LearningEventType.APPLICATION_SUBMITTED.name().equals(e.getEventType())) c[0]++;
            else if (LearningEventType.INTERVIEW_SCHEDULED.name().equals(e.getEventType())) c[1]++;
            else if (LearningEventType.OFFER_RECEIVED.name().equals(e.getEventType())) c[2]++;
        }

        return counts.entrySet().stream().map(entry -> {
            String version = entry.getKey();
            int applications = entry.getValue()[0];
            int interviews = entry.getValue()[1];
            int offers = entry.getValue()[2];
            BigDecimal atsAvg = atsScoreAvg(userId, version);
            BigDecimal interviewRate = ratio(interviews, applications);
            BigDecimal offerRate = ratio(offers, applications);
            return new VersionStats(version, applications, interviews, offers, atsAvg, interviewRate, offerRate);
        }).toList();
    }

    private BigDecimal atsScoreAvg(UUID userId, String resumeVersion) {
        UUID resumeTailoringId = tryParseUuid(resumeVersion);
        if (resumeTailoringId == null) return null;
        List<ResumeAtsAnalysis> analyses = atsAnalyses.findByUserIdAndResumeTailoringId(userId, resumeTailoringId);
        List<Integer> scores = analyses.stream().map(ResumeAtsAnalysis::getAtsScore).filter(java.util.Objects::nonNull).toList();
        if (scores.isEmpty()) return null;
        double avg = scores.stream().mapToInt(Integer::intValue).average().orElse(0);
        return BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        if (denominator == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private static UUID tryParseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }
}
