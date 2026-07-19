package ai.careerpilot.workflow.career;

import ai.careerpilot.domain.CareerStrategy;
import ai.careerpilot.repo.CareerStrategyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gap C — parses the {@code career_roadmap} / {@code skill_gaps} keys the LangGraph
 * {@code career_strategy} agent already writes into a {@code WorkflowRun}'s persisted JSON state
 * blob (see {@code agent-service/app/agents/career_strategy.py}, out of scope here) and persists
 * them into the EXISTING single-row-per-user {@link CareerStrategy} entity via the EXISTING
 * {@link CareerStrategyRepository} — no parallel engine. Ships dark behind
 * {@code career.roadmap.persistence.enabled}; the caller ({@code WorkflowService}) invokes this
 * as an additive, try/catch-isolated side effect. The agent's JSON is not guaranteed to be
 * well-formed (it's LLM output), so every field access here is defensive.
 */
@Service
public class CareerRoadmapPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(CareerRoadmapPersistenceService.class);

    private final CareerStrategyRepository strategies;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean enabled;

    public CareerRoadmapPersistenceService(CareerStrategyRepository strategies,
                                           @Value("${career.roadmap.persistence.enabled:false}") boolean enabled) {
        this.strategies = strategies;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Extracts {@code career_roadmap}/{@code skill_gaps} from a workflow's state map and upserts
     * them into the user's {@link CareerStrategy} row (find-or-create by userId, same idempotent
     * upsert shape as {@code CareerStrategyEngine.recompute}). Never throws; a no-op when
     * disabled or the keys are absent/malformed.
     */
    @Transactional
    public CareerStrategy captureFromWorkflow(UUID userId, Map<String, Object> state) {
        if (!enabled || userId == null || state == null) return null;
        try {
            Object rawRoadmap = state.get("career_roadmap");
            if (!(rawRoadmap instanceof Map<?, ?> m) || m.isEmpty()) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> roadmap = (Map<String, Object>) m;

            CareerStrategy row = strategies.findByUserId(userId)
                    .orElseGet(() -> CareerStrategy.builder().userId(userId).build());

            row.setRoadmap3Month(joinBullets(roadmap.get("horizon_3_months")));
            row.setRoadmap6Month(joinBullets(roadmap.get("horizon_6_months")));
            row.setRoadmap12Month(joinBullets(roadmap.get("horizon_12_months")));

            Object gaps = state.get("skill_gaps");
            if (gaps != null) {
                row.setSkillGapsJson(writeJsonOrNull(gaps));
            }
            if (row.getComputedAt() == null) {
                row.setComputedAt(Instant.now());
            }

            CareerStrategy saved = strategies.save(row);
            log.info("CAREER_ROADMAP captured user={} strategyId={}", userId, saved.getId());
            return saved;
        } catch (Exception e) {
            log.warn("CAREER_ROADMAP capture failed user={}: {}", userId, e.toString());
            return null;
        }
    }

    private static String joinBullets(Object v) {
        if (!(v instanceof List<?> list) || list.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Object item : list) {
            if (item == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append("- ").append(item);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private String writeJsonOrNull(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            return null;
        }
    }
}
