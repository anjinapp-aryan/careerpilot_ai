package ai.careerpilot.review;

import ai.careerpilot.domain.*;
import ai.careerpilot.learning.api.LearningExplainContextService;
import ai.careerpilot.learning.api.LearningExplainContextService.LearningExplainContext;
import ai.careerpilot.packageintel.PackageValidationStatus;
import ai.careerpilot.repo.*;
import ai.careerpilot.review.reviewer.*;
import ai.careerpilot.review.reviewer.ConsistencyReviewer.ConsistencyResult;
import ai.careerpilot.review.reviewer.QualityReviewer.QualityResult;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7.12 — the AI Review Pipeline orchestrator: the final quality gate that REVIEWS the Phase 7.11
 * {@link ApplicationPackage} (never mutates it) before Human Review / Auto Apply. It gathers the
 * already-computed artifacts once, runs each enabled reviewer (resume → ATS → company fit → learning →
 * consistency → quality), computes the final verdict, and persists an {@link ApplicationReview} head +
 * an immutable {@link ApplicationReviewHistory} row.
 *
 * <p>Deterministic and fail-safe: reviewers are pure and never re-score the underlying engines; the
 * pipeline is gated dark by {@code application.review.enabled}; and the whole run is wrapped so a
 * failure records a BLOCKED review (never a fabricated READY) and never throws.
 */
@Service
public class ApplicationReviewPipeline {

    private static final Logger log = LoggerFactory.getLogger(ApplicationReviewPipeline.class);
    private static final String WORKFLOW_TYPE = "APPLICATION_REVIEW";

    private final ApplicationPackageRepository packages;
    private final ResumeTailoringRepository tailorings;
    private final ResumeAtsAnalysisRepository atsAnalyses;
    private final JobRecommendationRepository recommendations;
    private final JobRepository jobs;
    private final LearningExplainContextService learningContext;
    private final ApplicationReviewRepository reviews;
    private final ApplicationReviewHistoryRepository reviewHistory;

    private final ResumeReviewer resumeReviewer;
    private final AtsReviewer atsReviewer;
    private final CompanyFitReviewer companyFitReviewer;
    private final LearningReviewer learningReviewer;
    private final ConsistencyReviewer consistencyReviewer;
    private final QualityReviewer qualityReviewer;

    private final WorkflowCorrelationService correlation;
    private final ReviewMetrics metrics;
    private final boolean enabled;

    public ApplicationReviewPipeline(ApplicationPackageRepository packages, ResumeTailoringRepository tailorings,
                                     ResumeAtsAnalysisRepository atsAnalyses, JobRecommendationRepository recommendations,
                                     JobRepository jobs, LearningExplainContextService learningContext,
                                     ApplicationReviewRepository reviews, ApplicationReviewHistoryRepository reviewHistory,
                                     ResumeReviewer resumeReviewer, AtsReviewer atsReviewer,
                                     CompanyFitReviewer companyFitReviewer, LearningReviewer learningReviewer,
                                     ConsistencyReviewer consistencyReviewer, QualityReviewer qualityReviewer,
                                     WorkflowCorrelationService correlation, ReviewMetrics metrics,
                                     @Value("${application.review.enabled:false}") boolean enabled) {
        this.packages = packages;
        this.tailorings = tailorings;
        this.atsAnalyses = atsAnalyses;
        this.recommendations = recommendations;
        this.jobs = jobs;
        this.learningContext = learningContext;
        this.reviews = reviews;
        this.reviewHistory = reviewHistory;
        this.resumeReviewer = resumeReviewer;
        this.atsReviewer = atsReviewer;
        this.companyFitReviewer = companyFitReviewer;
        this.learningReviewer = learningReviewer;
        this.consistencyReviewer = consistencyReviewer;
        this.qualityReviewer = qualityReviewer;
        this.correlation = correlation;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Review the package for a (user, job)'s latest package. Empty when disabled or no package exists. */
    @Transactional
    public Optional<ApplicationReview> reviewForJob(UUID userId, UUID jobId) {
        if (!enabled) return Optional.empty();
        ApplicationPackage pkg = packages.findByUserIdAndJobId(userId, jobId).orElse(null);
        if (pkg == null) return Optional.empty();
        return review(pkg.getId(), null);
    }

    /**
     * Run the review pipeline over one package. {@code correlationId} carries the workflow correlation
     * forward (from the validation event); may be null. Never throws — on failure records BLOCKED.
     */
    @Transactional
    public Optional<ApplicationReview> review(UUID applicationPackageId, UUID correlationId) {
        if (!enabled) return Optional.empty();
        long start = System.currentTimeMillis();
        ApplicationPackage pkg = packages.findById(applicationPackageId).orElse(null);
        if (pkg == null) return Optional.empty();
        UUID userId = pkg.getUserId();
        UUID jobId = pkg.getJobId();
        UUID cid = correlationId != null ? correlationId
                : correlation.start(WORKFLOW_TYPE, userId, jobId, pkg.getApplicationId());
        try {
            ReviewContext ctx = gather(pkg, userId, jobId);
            List<ReviewSection> sections = new ArrayList<>();

            ReviewSection resume = resumeReviewer.isEnabled() ? record(resumeReviewer.review(ctx)) : null;
            ReviewSection ats = atsReviewer.isEnabled() ? record(atsReviewer.review(ctx)) : null;
            ReviewSection company = companyFitReviewer.isEnabled() ? record(companyFitReviewer.review(ctx)) : null;
            ReviewSection learning = learningReviewer.isEnabled() ? record(learningReviewer.review(ctx)) : null;
            addIfPresent(sections, resume, ats, company, learning);

            ConsistencyResult consistency = consistencyReviewer.isEnabled()
                    ? consistencyReviewer.review(ctx) : new ConsistencyResult(ConsistencyStatus.PASS, List.of());
            if (consistencyReviewer.isEnabled()) metrics.recordReviewerRun(ConsistencyReviewer.NAME);

            QualityResult quality = qualityReviewer.isEnabled()
                    ? qualityReviewer.evaluate(score(resume), score(ats), score(company), score(learning), consistency.status())
                    : new QualityResult(0, QualityCategory.WEAK);
            if (qualityReviewer.isEnabled()) {
                metrics.recordReviewerRun(QualityReviewer.NAME);
                metrics.recordQuality(quality.score());
            }

            PackageValidationStatus verdict = finalVerdict(quality, consistency.status(), pkg.getValidationStatus());
            int confidence = quality.score();
            String reasons = buildReasons(sections, consistency, quality);

            ApplicationReview saved = persist(pkg, cid, resume, ats, company, learning, consistency, quality, verdict, confidence, reasons);
            metrics.recordReview();
            metrics.recordVerdict(verdict);
            correlation.advance(cid, "REVIEWED", verdict.name());
            log.info("APP_REVIEW user={} job={} version={} verdict={} quality={} consistency={}",
                    userId, jobId, pkg.getPackageVersion(), verdict, quality.score(), consistency.status());
            return Optional.of(saved);
        } catch (Exception e) {
            metrics.recordFailure();
            ApplicationReview blocked = recordBlockedFailure(pkg, cid, e);
            correlation.advance(cid, "REVIEWED", PackageValidationStatus.BLOCKED.name());
            log.warn("APP_REVIEW error package={}: {}", applicationPackageId, e.toString());
            return Optional.ofNullable(blocked);
        } finally {
            metrics.recordLatency(System.currentTimeMillis() - start);
        }
    }

    public Optional<ApplicationReview> latest(UUID applicationPackageId) {
        return reviews.findByApplicationPackageId(applicationPackageId);
    }

    public List<ApplicationReviewHistory> history(UUID applicationPackageId) {
        return reviewHistory.findByApplicationPackageIdOrderByCreatedAtDesc(applicationPackageId);
    }

    private ReviewContext gather(ApplicationPackage pkg, UUID userId, UUID jobId) {
        ResumeTailoring tailoring = tailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId).orElse(null);
        ResumeAtsAnalysis ats = atsAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId).orElse(null);
        JobRecommendation rec = recommendations.findByUserIdAndJobId(userId, jobId).orElse(null);
        Job job = jobs.findById(jobId).orElse(null);
        boolean companyAvailable = Boolean.TRUE.equals(pkg.getCompanyResearchAvailable());
        LearningExplainContext learning = learningContext.isEnabled() ? learningContext.get(userId) : null;
        return new ReviewContext(pkg, tailoring, ats, rec, companyAvailable, learning, job);
    }

    private ReviewSection record(ReviewSection section) {
        if (section != null) metrics.recordReviewerRun(section.reviewer());
        return section;
    }

    private static void addIfPresent(List<ReviewSection> sections, ReviewSection... items) {
        for (ReviewSection s : items) {
            if (s != null) sections.add(s);
        }
    }

    private static Integer score(ReviewSection s) {
        return s == null ? null : s.score();
    }

    /**
     * READY requires strong quality and no consistency failure and a non-BLOCKED package validation;
     * a FAIL / BLOCKED-category / BLOCKED-validation is BLOCKED; everything else is HUMAN_REVIEW. Fails
     * safe — an absent quality signal never reaches READY.
     */
    static PackageValidationStatus finalVerdict(QualityResult quality, ConsistencyStatus consistency,
                                                String packageValidationStatus) {
        boolean packageBlocked = PackageValidationStatus.BLOCKED.name().equals(packageValidationStatus);
        if (consistency == ConsistencyStatus.FAIL || quality.category() == QualityCategory.BLOCKED || packageBlocked) {
            return PackageValidationStatus.BLOCKED;
        }
        boolean strong = quality.category() == QualityCategory.STRONG || quality.category() == QualityCategory.EXCELLENT;
        if (strong && consistency == ConsistencyStatus.PASS && quality.score() >= 75) {
            return PackageValidationStatus.READY;
        }
        return PackageValidationStatus.HUMAN_REVIEW;
    }

    private ApplicationReview persist(ApplicationPackage pkg, UUID cid, ReviewSection resume, ReviewSection ats,
                                      ReviewSection company, ReviewSection learning, ConsistencyResult consistency,
                                      QualityResult quality, PackageValidationStatus verdict, int confidence, String reasons) {
        ApplicationReview head = reviews.findByApplicationPackageId(pkg.getId()).orElse(null);
        int nextVersion = head == null ? 1 : head.getReviewVersion() + 1;
        if (head == null) {
            head = ApplicationReview.builder().applicationPackageId(pkg.getId())
                    .userId(pkg.getUserId()).jobId(pkg.getJobId()).build();
        }
        head.setPackageVersion(pkg.getPackageVersion());
        head.setResumeScore(score(resume));
        head.setAtsScore(score(ats));
        head.setCompanyFitScore(score(company));
        head.setLearningConfidence(score(learning));
        head.setConsistencyStatus(consistency.status().name());
        head.setQualityScore(quality.score());
        head.setQualityCategory(quality.category().name());
        head.setVerdict(verdict.name());
        head.setConfidence(confidence);
        head.setReasons(reasons);
        head.setCorrelationId(cid);
        head.setReviewVersion(nextVersion);
        ApplicationReview saved = reviews.save(head);

        reviewHistory.save(ApplicationReviewHistory.builder()
                .applicationReviewId(saved.getId())
                .applicationPackageId(pkg.getId())
                .userId(pkg.getUserId()).jobId(pkg.getJobId())
                .packageVersion(pkg.getPackageVersion())
                .reviewVersion(nextVersion)
                .resumeScore(saved.getResumeScore()).atsScore(saved.getAtsScore())
                .companyFitScore(saved.getCompanyFitScore()).learningConfidence(saved.getLearningConfidence())
                .consistencyStatus(saved.getConsistencyStatus())
                .qualityScore(saved.getQualityScore()).qualityCategory(saved.getQualityCategory())
                .verdict(saved.getVerdict()).confidence(saved.getConfidence())
                .reasons(saved.getReasons()).correlationId(cid)
                .build());
        return saved;
    }

    private ApplicationReview recordBlockedFailure(ApplicationPackage pkg, UUID cid, Exception e) {
        if (pkg == null) return null;
        try {
            ApplicationReview head = reviews.findByApplicationPackageId(pkg.getId())
                    .orElse(ApplicationReview.builder().applicationPackageId(pkg.getId())
                            .userId(pkg.getUserId()).jobId(pkg.getJobId()).build());
            head.setPackageVersion(pkg.getPackageVersion());
            head.setConsistencyStatus(ConsistencyStatus.FAIL.name());
            head.setQualityCategory(QualityCategory.BLOCKED.name());
            head.setVerdict(PackageValidationStatus.BLOCKED.name());
            head.setReasons("[{\"reviewer\":\"pipeline\",\"error\":\"" + safe(e.toString()) + "\"}]");
            head.setCorrelationId(cid);
            head.setReviewVersion(head.getReviewVersion() == null ? 1 : head.getReviewVersion() + 1);
            return reviews.save(head);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String buildReasons(List<ReviewSection> sections, ConsistencyResult consistency, QualityResult quality) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ReviewSection s : sections) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"reviewer\":\"").append(s.reviewer()).append("\",\"score\":")
                    .append(s.score() == null ? "null" : s.score())
                    .append(",\"reasons\":").append(jsonArray(s.reasons())).append('}');
        }
        if (!first) sb.append(',');
        sb.append("{\"reviewer\":\"consistency\",\"status\":\"").append(consistency.status())
                .append("\",\"reasons\":").append(jsonArray(consistency.reasons())).append("},");
        sb.append("{\"reviewer\":\"quality\",\"score\":").append(quality.score())
                .append(",\"category\":\"").append(quality.category()).append("\"}");
        return sb.append(']').toString();
    }

    private static String jsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(safe(items.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
