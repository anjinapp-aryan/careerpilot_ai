package ai.careerpilot.execution.verification.evidence;

import org.springframework.stereotype.Service;

/**
 * Phase 0 — turns an {@link EvidenceBundle} into a {@link ConfidenceLevel}. Pure, stateless, and
 * deterministic: no repository access, no browser access, no LLM. Same discipline as {@code
 * RetryPolicyService.decide}, {@code MissionOrchestratorService.decide} and {@code
 * RecommendedActionEngine} — a fixed ladder over already-observed facts, never a guessed ranking.
 *
 * <p>The ladder, in evaluation order:
 * <ol>
 *   <li>Any {@link SignalType#ERROR_STATE} ⇒ {@link ConfidenceLevel#NONE}. A detected failure
 *       overrides every positive signal — a page can render a confirmation template <em>and</em> a
 *       validation error, and in that case we have not submitted anything.</li>
 *   <li>{@code decisive ≥ 1} and {@code decisive + strong ≥ 2} ⇒ {@link ConfidenceLevel#CONFIRMED}.</li>
 *   <li>{@code decisive ≥ 1}, or {@code strong ≥ 2} ⇒ {@link ConfidenceLevel#STRONG}.</li>
 *   <li>Exactly one strong signal, or corroborating-only ⇒ {@link ConfidenceLevel#WEAK}.</li>
 *   <li>Nothing ⇒ {@link ConfidenceLevel#NONE}.</li>
 * </ol>
 *
 * <p>{@link SignalStrength#CORROBORATING} signals (screenshots) never raise the level. They are
 * retained for human audit, and a bundle containing only screenshots is {@code WEAK}, not proof.
 */
@Service
public class VerificationAdjudicator {

    public ConfidenceLevel adjudicate(EvidenceBundle bundle) {
        if (bundle == null || bundle.isEmpty()) {
            return ConfidenceLevel.NONE;
        }
        if (bundle.has(SignalType.ERROR_STATE)) {
            return ConfidenceLevel.NONE;
        }

        long decisive = bundle.countOf(SignalStrength.DECISIVE);
        long strong = bundle.countOf(SignalStrength.STRONG);
        long positive = decisive + strong;

        if (decisive >= 1 && positive >= 2) {
            return ConfidenceLevel.CONFIRMED;
        }
        if (decisive >= 1 || strong >= 2) {
            return ConfidenceLevel.STRONG;
        }
        if (positive == 1 || bundle.countOf(SignalStrength.CORROBORATING) > 0) {
            return ConfidenceLevel.WEAK;
        }
        return ConfidenceLevel.NONE;
    }

    /**
     * Human-readable justification for the audit trail, so a verdict is never recorded without the
     * reasoning that produced it.
     */
    public String explain(EvidenceBundle bundle, ConfidenceLevel level) {
        if (bundle == null || bundle.isEmpty()) {
            return "no verification signals were collected";
        }
        if (bundle.has(SignalType.ERROR_STATE)) {
            return "a failure indicator was detected in the post-submit page; treated as not submitted "
                    + "regardless of other signals [" + bundle.describe() + "]";
        }
        return switch (level) {
            case CONFIRMED -> "an application reference plus corroborating evidence [" + bundle.describe() + "]";
            case STRONG -> "multiple independent success signals, no application reference [" + bundle.describe() + "]";
            case WEAK -> "only one success signal; the submit click happened but the outcome is unproven ["
                    + bundle.describe() + "]";
            case NONE -> "no positive evidence [" + bundle.describe() + "]";
        };
    }
}
