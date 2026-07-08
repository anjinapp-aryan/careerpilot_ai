package ai.careerpilot.packageintel;

import ai.careerpilot.autopilot.decision.ApplicationDecisionEngine;
import ai.careerpilot.autopilot.research.CompanyResearchEngine;
import ai.careerpilot.autopilot.research.CompanyResearchEngine.CompanyResearch;
import ai.careerpilot.domain.*;
import ai.careerpilot.packageintel.event.ApplicationPackageValidatedEvent;
import ai.careerpilot.packageintel.ApplicationPackageValidator.Check;
import ai.careerpilot.packageintel.ApplicationPackageValidator.ValidationResult;
import ai.careerpilot.packageintel.ApplicationPackageValidator.ValidationSignals;
import ai.careerpilot.repo.*;
import ai.careerpilot.resumetailoring.apppackage.ApplicationPackageService;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7.11 — the intelligence layer that turns a raw Phase 2D.6 {@link ApplicationPackage} into the
 * canonical, validated pre-submission source of truth. It does NOT re-assemble or re-score anything:
 * it {@link ApplicationPackageService#assemble delegates} package assembly to the existing 2D.6
 * service, then <em>binds</em> the already-computed signals from other engines onto the package
 * (autonomous {@link ApplicationDecision}, company research, Phase 6.5 learning boost, recommendation
 * strength, resume match summary, workflow correlation), runs the {@link ApplicationPackageValidator},
 * and records an immutable {@link ApplicationPackageValidation} history row.
 *
 * <p>Fail-safe by construction: gated dark by {@code application.package.validation.enabled}; every
 * public entry point is a no-op returning empty when disabled; and the whole enrich/validate body is
 * wrapped so a failure records a BLOCKED validation (never a fabricated READY) and never throws.
 */
@Service
public class ApplicationPackageIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationPackageIntelligenceService.class);
    private static final String WORKFLOW_TYPE = "APPLICATION_PACKAGE";
    private static final int SKILL_FLOOR = 50;

    private final ApplicationPackageService packageService;
    private final ApplicationPackageRepository packages;
    private final ApplicationPackageVersionRepository versions;
    private final ApplicationPackageValidationRepository validations;
    private final ApplicationDecisionEngine decisionEngine;
    private final CompanyResearchEngine companyResearch;
    private final JobRecommendationRepository recommendations;
    private final ResumeAtsAnalysisRepository atsAnalyses;
    private final JobRepository jobs;
    private final ApplicationPackageValidator validator;
    private final WorkflowCorrelationService correlation;
    private final PackageIntelligenceMetrics metrics;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final ObjectMapper mapper = new ObjectMapper();

    private final boolean enabled;
    private final boolean requireLearning;

    public ApplicationPackageIntelligenceService(ApplicationPackageService packageService,
                                                 ApplicationPackageRepository packages,
                                                 ApplicationPackageVersionRepository versions,
                                                 ApplicationPackageValidationRepository validations,
                                                 ApplicationDecisionEngine decisionEngine,
                                                 CompanyResearchEngine companyResearch,
                                                 JobRecommendationRepository recommendations,
                                                 ResumeAtsAnalysisRepository atsAnalyses,
                                                 JobRepository jobs,
                                                 ApplicationPackageValidator validator,
                                                 WorkflowCorrelationService correlation,
                                                 PackageIntelligenceMetrics metrics,
                                                 org.springframework.context.ApplicationEventPublisher events,
                                                 @Value("${application.package.validation.enabled:false}") boolean enabled,
                                                 @Value("${application.package.validation.require-learning:false}") boolean requireLearning) {
        this.packageService = packageService;
        this.packages = packages;
        this.versions = versions;
        this.validations = validations;
        this.decisionEngine = decisionEngine;
        this.companyResearch = companyResearch;
        this.recommendations = recommendations;
        this.atsAnalyses = atsAnalyses;
        this.jobs = jobs;
        this.validator = validator;
        this.correlation = correlation;
        this.metrics = metrics;
        this.events = events;
        this.enabled = enabled;
        this.requireLearning = requireLearning;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Assemble (via 2D.6) and then enrich+validate a package for (user, job). Empty when validation is
     * disabled, or when the 2D.6 assembler produced nothing (no tailored resume yet). POST-generate path.
     */
    @Transactional
    public Optional<ApplicationPackage> generate(UUID userId, UUID jobId) {
        if (!enabled) return Optional.empty();
        Optional<ApplicationPackage> assembled = packageService.assemble(userId, jobId);
        return assembled.flatMap(pkg -> enrichAndValidate(pkg.getId(), null));
    }

    /**
     * Enrich + validate an already-assembled package by id (the event-driven path). {@code correlationId}
     * carries the workflow correlation forward when invoked from the validation worker; may be null.
     * Never throws — on failure it records a BLOCKED validation and returns the package unchanged.
     */
    @Transactional
    public Optional<ApplicationPackage> enrichAndValidate(UUID applicationPackageId, UUID correlationId) {
        if (!enabled) return Optional.empty();
        long start = System.currentTimeMillis();
        ApplicationPackage pkg = packages.findById(applicationPackageId).orElse(null);
        if (pkg == null) return Optional.empty();
        UUID userId = pkg.getUserId();
        UUID jobId = pkg.getJobId();
        UUID cid = correlationId != null ? correlationId
                : correlation.start(WORKFLOW_TYPE, userId, jobId, pkg.getApplicationId());
        try {
            JobRecommendation rec = recommendations.findByUserIdAndJobId(userId, jobId).orElse(null);
            Integer atsScore = atsAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)
                    .map(ResumeAtsAnalysis::getAtsScore).orElse(null);
            Job job = jobs.findById(jobId).orElse(null);
            ApplicationDecision decision = decisionEngine.latest(userId, jobId).orElse(null);

            boolean companyResearchAvailable = false;
            String companyResearchSummary = null;
            if (companyResearch.isEnabled()) {
                Optional<CompanyResearch> research = companyResearch.research(jobId);
                companyResearchAvailable = research.isPresent();
                companyResearchSummary = research.map(CompanyResearch::summary).orElse(null);
            }

            int learningBoost = learningBoost(rec);
            boolean learningAvailable = rec != null && rec.getScoreBreakdown() != null
                    && !rec.getScoreBreakdown().isBlank();
            int matchScore = rec != null ? rec.getMatchScore() : 0;

            ValidationSignals signals = new ValidationSignals(
                    pkg.getResumeId() != null,
                    pkg.getResumeTailoringId() != null,
                    pkg.getAtsAnalysisId() != null,
                    rec != null,
                    companyResearchAvailable,
                    companyResearch.isEnabled(),
                    learningAvailable,
                    requireLearning,
                    rec != null && matchScore >= SKILL_FLOOR,
                    job != null && job.getTitle() != null && pkg.getResumeId() != null);
            ValidationResult result = validator.evaluate(signals);

            pkg.setApplicationDecisionId(decision != null ? decision.getId() : null);
            pkg.setCompanyResearchAvailable(companyResearchAvailable);
            pkg.setCompanyResearchSummary(companyResearchSummary);
            pkg.setLearningBoost(learningBoost);
            pkg.setRecommendationStrength(recommendationStrength(matchScore));
            pkg.setMatchSummary(matchSummary(matchScore, atsScore, learningBoost, decision));
            pkg.setCorrelationId(cid);
            pkg.setValidationStatus(result.status().name());
            ApplicationPackage saved = packages.save(pkg);

            validations.save(ApplicationPackageValidation.builder()
                    .applicationPackageId(saved.getId())
                    .userId(userId).jobId(jobId)
                    .packageVersion(saved.getPackageVersion())
                    .status(result.status().name())
                    .blockingReason(result.blockingReason())
                    .checks(checksJson(result.checks()))
                    .correlationId(cid)
                    .build());

            metrics.recordGenerated();
            metrics.recordValidation(result.status());
            correlation.advance(cid, "VALIDATED", result.status().name());
            log.info("APP_PACKAGE_INTEL validated user={} job={} version={} status={}",
                    userId, jobId, saved.getPackageVersion(), result.status());
            // Phase 7.12 seam — chains the AI Review Pipeline after validation (consumer dark by default).
            events.publishEvent(new ApplicationPackageValidatedEvent(userId, jobId, saved.getId(),
                    saved.getPackageVersion() == null ? 0 : saved.getPackageVersion(),
                    result.status().name(), cid));
            return Optional.of(saved);
        } catch (Exception e) {
            metrics.recordFailure();
            recordBlockedFailure(pkg, cid, e);
            correlation.advance(cid, "VALIDATED", PackageValidationStatus.BLOCKED.name());
            log.warn("APP_PACKAGE_INTEL error package={}: {}", applicationPackageId, e.toString());
            return Optional.of(pkg);
        } finally {
            metrics.recordLatency(System.currentTimeMillis() - start);
        }
    }

    public Optional<ApplicationPackage> latest(UUID userId, UUID jobId) {
        return packages.findByUserIdAndJobId(userId, jobId);
    }

    /** The package that produced a given application — the seam Learning/Tracking use to attribute an outcome. */
    public Optional<ApplicationPackage> forApplication(UUID applicationId) {
        return packages.findFirstByApplicationIdOrderByPackageVersionDesc(applicationId);
    }

    public List<ApplicationPackageVersion> history(UUID applicationPackageId) {
        return versions.findByApplicationPackageIdOrderByPackageVersionDesc(applicationPackageId);
    }

    public Optional<ApplicationPackageVersion> version(UUID applicationPackageId, int packageVersion) {
        return versions.findByApplicationPackageIdAndPackageVersion(applicationPackageId, packageVersion);
    }

    public Optional<ApplicationPackageValidation> latestValidation(UUID applicationPackageId) {
        return validations.findFirstByApplicationPackageIdOrderByCreatedAtDesc(applicationPackageId);
    }

    public List<ApplicationPackageValidation> validationHistory(UUID applicationPackageId) {
        return validations.findByApplicationPackageIdOrderByCreatedAtDesc(applicationPackageId);
    }

    private void recordBlockedFailure(ApplicationPackage pkg, UUID cid, Exception e) {
        if (pkg == null) return;
        try {
            validations.save(ApplicationPackageValidation.builder()
                    .applicationPackageId(pkg.getId())
                    .userId(pkg.getUserId()).jobId(pkg.getJobId())
                    .packageVersion(pkg.getPackageVersion())
                    .status(PackageValidationStatus.BLOCKED.name())
                    .blockingReason("validation error: " + e)
                    .correlationId(cid)
                    .build());
        } catch (Exception ignored) {
            // last-resort sink already failing; nothing safe left to do.
        }
    }

    private static String recommendationStrength(int matchScore) {
        if (matchScore >= 90) return "STRONG";
        if (matchScore >= 75) return "MODERATE";
        if (matchScore >= 60) return "WEAK";
        return "LOW";
    }

    private static String matchSummary(int matchScore, Integer atsScore, int learningBoost, ApplicationDecision decision) {
        StringBuilder sb = new StringBuilder("match ").append(matchScore);
        if (atsScore != null) sb.append(", ATS ").append(atsScore);
        if (learningBoost != 0) sb.append(", learning ").append(learningBoost > 0 ? "+" : "").append(learningBoost);
        if (decision != null) sb.append(", decision ").append(decision.getOutcome());
        return sb.toString();
    }

    private String checksJson(List<Check> checks) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < checks.size(); i++) {
            Check c = checks.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(c.name()).append("\",\"passed\":").append(c.passed())
                    .append(",\"severity\":\"").append(c.severity()).append("\"}");
        }
        return sb.append(']').toString();
    }

    private int learningBoost(JobRecommendation rec) {
        if (rec == null || rec.getScoreBreakdown() == null || rec.getScoreBreakdown().isBlank()) return 0;
        try {
            JsonNode node = mapper.readTree(rec.getScoreBreakdown());
            JsonNode lb = node.get("learningBoost");
            return lb == null ? 0 : lb.asInt(0);
        } catch (Exception e) {
            return 0;
        }
    }
}
