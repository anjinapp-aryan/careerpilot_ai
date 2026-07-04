package ai.careerpilot.resumetailoring.autoapply;

import ai.careerpilot.domain.*;
import ai.careerpilot.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2D.7 — deterministic auto-apply readiness assessment of an assembled {@link
 * ApplicationPackage}. <b>PREPARATION ONLY</b> — no browser automation exists anywhere in the
 * codebase and this stage never contacts an external site; it only classifies:
 *
 * <ul>
 *   <li>{@code application_method} — EXTERNAL_URL when the job carries a source/external URL,
 *       EMAIL when the description exposes an application email, else UNKNOWN.</li>
 *   <li>{@code requires_login/questionnaire/upload} — keyword heuristics over the job posting
 *       (major ATS hosts require login; "questionnaire/screening questions" phrases; resume
 *       upload is assumed true unless the posting says email application).</li>
 *   <li>{@code readiness_score} 0–100 and a verdict: SAFE_TO_APPLY only for a COMPLETE package
 *       with a known method and no questionnaire; REQUIRES_REVIEW for complete-but-frictioned;
 *       MANUAL_ONLY when the package is incomplete or the method is unknown.</li>
 * </ul>
 *
 * Never throws; flag-gated dark by {@code auto.apply.package.enabled}.
 */
@Service
public class AutoApplyPreparationService {

    private static final Logger log = LoggerFactory.getLogger(AutoApplyPreparationService.class);

    private final ApplicationPackageRepository packages;
    private final JobRepository jobs;
    private final AutoApplyPackageRepository autoApplyPackages;
    private final AutoApplyPackageAuditRepository audit;
    private final AutoApplyPreparationMetrics metrics;
    private final boolean enabled;

    public AutoApplyPreparationService(ApplicationPackageRepository packages, JobRepository jobs,
                                       AutoApplyPackageRepository autoApplyPackages,
                                       AutoApplyPackageAuditRepository audit,
                                       AutoApplyPreparationMetrics metrics,
                                       @Value("${auto.apply.package.enabled:false}") boolean enabled) {
        this.packages = packages;
        this.jobs = jobs;
        this.autoApplyPackages = autoApplyPackages;
        this.audit = audit;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Prepare the auto-apply readiness assessment for a package. Empty when disabled/missing/failed. */
    @Transactional
    public Optional<AutoApplyPackage> prepare(UUID userId, UUID jobId, UUID applicationPackageId) {
        if (!enabled) return Optional.empty();
        long start = System.currentTimeMillis();
        metrics.recordRequest();
        try {
            ApplicationPackage pkg = applicationPackageId != null
                    ? packages.findById(applicationPackageId).orElse(null)
                    : packages.findByUserIdAndJobId(userId, jobId).orElse(null);
            Job job = jobs.findById(jobId).orElse(null);
            if (pkg == null || job == null) {
                metrics.recordFailure();
                record(userId, jobId, null, AutoApplyPackageAuditEntry.OUTCOME_ERROR, "missing package or job");
                return Optional.empty();
            }

            String url = job.getSourceUrl() != null ? job.getSourceUrl() : job.getExternalUrl();
            String jobText = lower(job.getDescription());

            String method = method(url, jobText);
            boolean requiresLogin = requiresLogin(url);
            boolean requiresQuestionnaire = jobText.contains("questionnaire")
                    || jobText.contains("screening question") || jobText.contains("assessment");
            boolean requiresUpload = !AutoApplyPackage.METHOD_EMAIL.equals(method);
            boolean packageComplete = ApplicationPackage.STATUS_ASSEMBLED.equals(pkg.getStatus());

            int readiness = readinessScore(packageComplete, method, requiresLogin, requiresQuestionnaire);
            String status = verdict(packageComplete, method, requiresLogin, requiresQuestionnaire);

            AutoApplyPackage saved = autoApplyPackages.save(AutoApplyPackage.builder()
                    .userId(userId).jobId(jobId)
                    .applicationPackageId(pkg.getId())
                    .applicationId(pkg.getApplicationId())
                    .provider(job.getSource())
                    .applicationMethod(method)
                    .requiresLogin(requiresLogin)
                    .requiresQuestionnaire(requiresQuestionnaire)
                    .requiresUpload(requiresUpload)
                    .readinessScore(readiness)
                    .status(status)
                    .build());

            switch (status) {
                case AutoApplyPackage.STATUS_SAFE_TO_APPLY -> metrics.recordSafeToApply();
                case AutoApplyPackage.STATUS_REQUIRES_REVIEW -> metrics.recordRequiresReview();
                default -> metrics.recordManualOnly();
            }
            metrics.recordSuccess();
            record(userId, jobId, saved.getId(), AutoApplyPackageAuditEntry.OUTCOME_PREPARED,
                    status + " readiness=" + readiness);
            log.info("AUTO_APPLY_PREP user={} job={} method={} readiness={} status={}",
                    userId, jobId, method, readiness, status);
            return Optional.of(saved);
        } catch (Exception e) {
            metrics.recordFailure();
            record(userId, jobId, null, AutoApplyPackageAuditEntry.OUTCOME_ERROR, e.toString());
            log.warn("AUTO_APPLY_PREP error user={} job={}: {}", userId, jobId, e.toString());
            return Optional.empty();
        } finally {
            metrics.recordLatency(System.currentTimeMillis() - start);
        }
    }

    public Optional<AutoApplyPackage> latest(UUID userId, UUID jobId) {
        return autoApplyPackages.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId);
    }

    // ── deterministic heuristics ──

    private static String method(String url, String jobText) {
        if (url != null && !url.isBlank()) return AutoApplyPackage.METHOD_EXTERNAL_URL;
        if (jobText.contains("apply by email") || jobText.contains("send your resume to")
                || jobText.matches(".*\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b.*")) {
            return AutoApplyPackage.METHOD_EMAIL;
        }
        return AutoApplyPackage.METHOD_UNKNOWN;
    }

    /** Major ATS hosts require an account; unknown hosts are assumed login-required (conservative). */
    private static boolean requiresLogin(String url) {
        if (url == null || url.isBlank()) return true;
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains("workday") || u.contains("greenhouse") || u.contains("lever.co")
                || u.contains("taleo") || u.contains("icims") || u.contains("successfactors")
                || u.contains("linkedin.com")) {
            return true;
        }
        // Aggregator/direct listings (remoteok, arbeitnow, plain career pages) usually don't.
        return !(u.contains("remoteok") || u.contains("arbeitnow") || u.contains("remotive"));
    }

    private static int readinessScore(boolean packageComplete, String method,
                                      boolean requiresLogin, boolean requiresQuestionnaire) {
        int score = 0;
        if (packageComplete) score += 50;
        if (!AutoApplyPackage.METHOD_UNKNOWN.equals(method)) score += 20;
        if (!requiresLogin) score += 15;
        if (!requiresQuestionnaire) score += 15;
        return score;
    }

    private static String verdict(boolean packageComplete, String method,
                                  boolean requiresLogin, boolean requiresQuestionnaire) {
        if (!packageComplete || AutoApplyPackage.METHOD_UNKNOWN.equals(method)) {
            return AutoApplyPackage.STATUS_MANUAL_ONLY;
        }
        if (requiresQuestionnaire || requiresLogin) {
            return AutoApplyPackage.STATUS_REQUIRES_REVIEW;
        }
        return AutoApplyPackage.STATUS_SAFE_TO_APPLY;
    }

    private void record(UUID userId, UUID jobId, UUID autoApplyPackageId, String outcome, String reason) {
        audit.save(AutoApplyPackageAuditEntry.builder()
                .userId(userId).jobId(jobId)
                .autoApplyPackageId(autoApplyPackageId)
                .outcome(outcome).reason(reason)
                .build());
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
