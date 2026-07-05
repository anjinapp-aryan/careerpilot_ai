package ai.careerpilot.discovery.relevance;

import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.CandidateSignalResolver;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.service.profile.JsonLists;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 3B.1 — single entry point tying candidate-signal resolution, {@link JobEligibilityEngine},
 * and {@link CareerRelevanceScore} together. This is the only class {@code JobService} and
 * {@code JobRelevanceController} need to call.
 *
 * <p>Master-flag gated: when {@code career.relevance.enabled} is false, {@link #isEnabled()}
 * returns false and callers must not invoke {@link #evaluate} — this class does not self-guard
 * every method so it stays a pure, easily-unit-tested computation; the flag check belongs at the
 * caller (matches the rest of this codebase's convention, e.g. {@code RecommendationDecisionService}).
 */
@Component
public class CareerRelevanceEvaluator {

    private final CandidateSignalResolver signalResolver;
    private final CandidateProfileRepository profiles;
    private final JobEligibilityEngine engine;
    private final CareerThresholdPolicy thresholdPolicy;
    private final boolean relevanceEnabled;
    private final boolean explainabilityEnabled;

    public CareerRelevanceEvaluator(CandidateSignalResolver signalResolver,
                                    CandidateProfileRepository profiles,
                                    JobEligibilityEngine engine,
                                    CareerThresholdPolicy thresholdPolicy,
                                    @Value("${career.relevance.enabled:false}") boolean relevanceEnabled,
                                    @Value("${career.explainability.enabled:false}") boolean explainabilityEnabled) {
        this.signalResolver = signalResolver;
        this.profiles = profiles;
        this.engine = engine;
        this.thresholdPolicy = thresholdPolicy;
        this.relevanceEnabled = relevanceEnabled;
        this.explainabilityEnabled = explainabilityEnabled;
    }

    public boolean isEnabled() { return relevanceEnabled; }
    public boolean isExplainabilityEnabled() { return explainabilityEnabled; }

    /** Resolve this user's candidate context once, to score/evaluate many jobs cheaply. */
    public RelevanceCandidateContext resolveContext(UUID userId) {
        var signals = signalResolver.resolve(userId).orElse(null);
        List<String> preferredDomains = preferredDomains(userId);

        if (signals == null) {
            return new RelevanceCandidateContext(null, List.of(), null, preferredDomains);
        }
        return new RelevanceCandidateContext(
                signals.targetRole(), signals.skills(), signals.yearsExperience(), preferredDomains);
    }

    /** Score one job against an already-resolved candidate context (feed-filter hot path). */
    public CareerRelevanceScore score(Job job, RelevanceCandidateContext ctx) {
        return CareerRelevanceScore.from(engine.evaluate(job, ctx));
    }

    /**
     * Phase 3B.1.1 — scope-aware evaluation: same underlying score, but visibility comes from
     * {@link CareerThresholdPolicy} instead of the flat {@code >= 70} cutoff. {@code scope} is
     * {@code "domestic"} or {@code "international"} (see {@link CareerThresholdPolicy#isVisibleForScope}).
     */
    public CareerRelevanceResult evaluateForScope(Job job, RelevanceCandidateContext ctx, String scope) {
        var result = engine.evaluate(job, ctx);
        CareerRelevanceScore score = CareerRelevanceScore.from(result);
        boolean visible = thresholdPolicy.isVisibleForScope(scope, score.relevanceScore());
        return new CareerRelevanceResult(score.relevanceScore(),
                CareerMatchStrength.fromScore(score.relevanceScore()), visible, reasons(result, ctx, job));
    }

    /** Full explainability payload for one (user, job), or empty when explainability is disabled. */
    public Optional<CareerRelevanceExplanation> explain(UUID userId, Job job) {
        if (!explainabilityEnabled) return Optional.empty();
        RelevanceCandidateContext ctx = resolveContext(userId);
        var result = engine.evaluate(job, ctx);
        CareerRelevanceScore score = CareerRelevanceScore.from(result);
        return Optional.of(new CareerRelevanceExplanation(
                score.relevanceScore(), score.matchStrength(),
                score.roleMatch(), score.skillOverlap(), score.experienceFit(), score.domainFit(),
                reasons(result, ctx, job)));
    }

    private List<String> preferredDomains(UUID userId) {
        return profiles.findByUserId(userId)
                .map(CareerRelevanceEvaluator::combinedDomains)
                .orElse(List.of());
    }

    private static List<String> combinedDomains(CandidateProfile p) {
        List<String> out = new ArrayList<>(JsonLists.toList(p.getDomainsJson()));
        out.addAll(JsonLists.toList(p.getIndustriesJson()));
        return out;
    }

    private static List<String> reasons(JobEligibilityEngine.Result result,
                                        RelevanceCandidateContext ctx, Job job) {
        List<String> reasons = new ArrayList<>();
        if (result.roleMatch()) {
            String role = ctx.targetRole();
            reasons.add(role != null && !role.isBlank()
                    ? "Matches " + role.trim() + " profile"
                    : "Role family aligned");
        } else {
            reasons.add("Role does not match your target profile");
        }
        reasons.add(result.skillOverlap() + "% skill overlap");
        reasons.add(result.experienceMatch() ? "Experience aligned" : "Experience below target level");
        reasons.add(result.domainMatch() ? "Preferred industry" : "Excluded industry/domain");
        return reasons;
    }
}
