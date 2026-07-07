package ai.careerpilot.learning.resume;

import ai.careerpilot.domain.ResumeLearning;
import ai.careerpilot.repo.ResumeLearningRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Phase 6.5 — recomputes {@link ResumeLearning} rows from {@link ResumePerformanceAnalyzer} stats
 * and determines the best-performing version (highest offer rate, ties broken by interview rate).
 */
@Service
public class ResumeLearningService {

    private final ResumePerformanceAnalyzer analyzer;
    private final ResumeLearningRepository resumeLearning;

    public ResumeLearningService(ResumePerformanceAnalyzer analyzer, ResumeLearningRepository resumeLearning) {
        this.analyzer = analyzer;
        this.resumeLearning = resumeLearning;
    }

    @Transactional
    public void recompute(UUID userId) {
        List<ResumePerformanceAnalyzer.VersionStats> stats = analyzer.analyze(userId);
        if (stats.isEmpty()) return;

        String bestVersion = stats.stream()
                .max(Comparator.comparing((ResumePerformanceAnalyzer.VersionStats s) -> nz(s.offerRate()))
                        .thenComparing(s -> nz(s.interviewRate())))
                .map(ResumePerformanceAnalyzer.VersionStats::resumeVersion)
                .orElse(null);

        for (ResumePerformanceAnalyzer.VersionStats s : stats) {
            ResumeLearning row = resumeLearning.findByUserIdAndResumeVersion(userId, s.resumeVersion())
                    .orElseGet(() -> ResumeLearning.builder().userId(userId).resumeVersion(s.resumeVersion()).build());
            row.setApplications(s.applications());
            row.setInterviews(s.interviews());
            row.setOffers(s.offers());
            row.setAtsScoreAvg(s.atsScoreAvg());
            row.setInterviewRate(s.interviewRate());
            row.setOfferRate(s.offerRate());
            row.setBestVersion(s.resumeVersion().equals(bestVersion));
            row.setComputedAt(Instant.now());
            resumeLearning.save(row);
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
