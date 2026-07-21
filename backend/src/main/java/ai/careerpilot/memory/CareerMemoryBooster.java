package ai.careerpilot.memory;

import ai.careerpilot.domain.CareerDecisionMemory;
import ai.careerpilot.domain.Job;
import ai.careerpilot.repo.CareerDecisionMemoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 7.15.1/7.15.2 recommendation integration — a small additive boost/penalty for jobs the
 * candidate has an explicit remembered decision about, applied by {@code JobMatchingService} in
 * the SAME seam, immediately after {@code applyCompanyKnowledgeBoost} — same clamping discipline
 * (±{@value #MAX_BOOST}), same "0 when dark" contract. This is NOT a new recommendation engine:
 * it extends the exact boost-chaining pattern already established by
 * {@code LearningRecommendationBooster} (Phase 6.5) and {@code CompanyKnowledgeBooster} (Phase
 * 7.13) — {@code JobMatchingService.scoreV2} composes all three the same way.
 *
 * <p>Two signal sources, both bounded and both requiring an EXPLICIT remembered decision (never
 * an inference the candidate didn't actually state):
 * <ul>
 *   <li>Company-level: {@code JOB_REJECTED}/{@code JOB_APPROVED}/{@code JOB_SAVED} memories
 *       (from {@code RecommendationFeedback}) whose value names this job's company.</li>
 *   <li>Technology-level (Phase 7.15.2): {@code TECHNOLOGY_*} memories (from
 *       {@code ConversationIntelligenceWorker} — e.g. "I don't want React anymore") whose value
 *       names one of this job's skills — POSITIVE nudges the job up, NEGATIVE nudges it down.</li>
 * </ul>
 * Scope, stated honestly: still no reasoning about relocation-heavy postings or country
 * preference drift — those would need structured job attributes this schema doesn't have yet,
 * and a wrong inference here would silently mis-rank jobs. Gated by
 * {@code jobs.matching.career-memory-boost.enabled}, default {@code false}, independent of
 * {@code career.memory.enabled} (writing) and {@code copilot.career-memory-context.enabled}
 * (Copilot exposure) — three separate switches for three separate blast radii.
 */
@Component
public class CareerMemoryBooster {

    static final int MAX_BOOST = 5;
    private static final int LOOKBACK = 50; // most-recent memories considered per user, bounded read

    private final CareerDecisionMemoryRepository memories;
    private final boolean memoryEnabled;
    private final boolean boostEnabled;

    public CareerMemoryBooster(CareerDecisionMemoryRepository memories,
                               @Value("${career.memory.enabled:false}") boolean memoryEnabled,
                               @Value("${jobs.matching.career-memory-boost.enabled:false}") boolean boostEnabled) {
        this.memories = memories;
        this.memoryEnabled = memoryEnabled;
        this.boostEnabled = boostEnabled;
    }

    public boolean isActive() {
        return memoryEnabled && boostEnabled;
    }

    /** Boost for one job — company + technology signals combined, clamped to ±{@value #MAX_BOOST}. Never throws. */
    public int computeBoost(UUID userId, Job job) {
        if (!isActive() || job == null) return 0;
        try {
            List<CareerDecisionMemory> recent = memories.findActiveByUserId(userId, Instant.now());
            String company = job.getCompany() == null ? null : job.getCompany().toLowerCase();
            String haystack = ((job.getTitle() == null ? "" : job.getTitle()) + " "
                    + (job.getSkills() == null ? "" : job.getSkills())).toLowerCase();

            int boost = 0;
            int checked = 0;
            for (CareerDecisionMemory m : recent) {
                if (checked++ >= LOOKBACK) break;
                if (m.getValue() == null) continue;
                String needle = m.getValue().toLowerCase();

                if (company != null && !company.isBlank() && needle.contains(company)) {
                    boost += switch (m.getDecisionType()) {
                        case "JOB_REJECTED" -> -3;
                        case "JOB_APPROVED" -> 2;
                        case "JOB_SAVED" -> 1;
                        default -> 0;
                    };
                }
                if ("TECHNOLOGY".equals(m.getCategory()) && haystack.contains(needle)) {
                    if (m.getDecisionType().endsWith("_POSITIVE")) boost += 2;
                    else if (m.getDecisionType().endsWith("_NEGATIVE")) boost -= 2;
                }
            }
            return Math.max(-MAX_BOOST, Math.min(MAX_BOOST, boost));
        } catch (Exception e) {
            return 0; // memory must never break matching
        }
    }
}
