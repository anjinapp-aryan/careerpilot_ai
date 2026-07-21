package ai.careerpilot.offer;

import ai.careerpilot.memory.CareerMemoryService;
import ai.careerpilot.repo.OfferRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gap B — parses the {@code salary_insights} key the LangGraph {@code salary_intelligence} agent
 * already writes into a {@code WorkflowRun}'s persisted JSON state blob (see
 * {@code agent-service/app/agents/salary_intelligence.py}, out of scope here) and captures it as
 * an {@link Offer} row. Ships dark behind {@code offer.intelligence.enabled}; the caller
 * ({@code WorkflowService}) invokes this as an additive, try/catch-isolated side effect — a
 * failure here must never affect the workflow response. The agent's JSON is not guaranteed to be
 * well-formed (it's LLM output), so every field access here is defensive.
 */
@Service
public class OfferAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(OfferAnalysisService.class);

    private final OfferRepository offers;
    private final CareerMemoryService careerMemory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean enabled;

    public OfferAnalysisService(OfferRepository offers, CareerMemoryService careerMemory,
                                @Value("${offer.intelligence.enabled:false}") boolean enabled) {
        this.offers = offers;
        this.careerMemory = careerMemory;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Extracts {@code salary_insights} from a workflow's state map and upserts it as an
     * agent-sourced {@link Offer} keyed on (userId, threadId) — idempotent across repeated
     * WorkflowService transitions on the same run. Returns {@code null} (never throws) when
     * disabled, the key is absent/malformed, or persistence fails.
     */
    @Transactional
    public Offer captureFromWorkflow(UUID userId, String threadId, Map<String, Object> state) {
        if (!enabled || userId == null || state == null) return null;
        try {
            Object raw = state.get("salary_insights");
            if (!(raw instanceof Map<?, ?> m) || m.isEmpty()) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> insights = (Map<String, Object>) m;

            Offer offer = offers.findByUserIdAndSourceThreadId(userId, threadId)
                    .orElseGet(() -> Offer.builder()
                            .userId(userId)
                            .source("SALARY_INTELLIGENCE_AGENT")
                            .sourceThreadId(threadId)
                            .build());

            offer.setCurrency(asString(insights.get("currency")));
            offer.setMarketP25(asBigDecimal(insights.get("p25")));
            offer.setMarketP50(asBigDecimal(insights.get("p50")));
            offer.setMarketP75(asBigDecimal(insights.get("p75")));
            offer.setMarketP90(asBigDecimal(insights.get("p90")));
            offer.setNegotiationStrategy(joinBullets(insights.get("negotiation_strategy")));
            offer.setLeveragePoints(writeJsonOrNull(insights.get("leverage_points")));

            Object targetRole = state.get("target_role");
            if (offer.getCompanyName() == null && targetRole != null) {
                offer.setCompanyName("Target: " + targetRole);
            }

            Offer saved = offers.save(offer);
            log.info("OFFER_INTEL captured user={} thread={} offerId={}", userId, threadId, saved.getId());
            // Additive Phase 7.15.1 side effect — never blocks or fails the offer capture.
            try {
                String value = saved.getCompanyName() != null ? saved.getCompanyName()
                        : (saved.getMarketP50() != null
                                ? saved.getMarketP50() + (saved.getCurrency() != null ? " " + saved.getCurrency() : "")
                                : null);
                careerMemory.record(userId, "OFFER_ANALYZED", "SALARY", value, saved.getNegotiationStrategy(),
                        BigDecimal.ONE, "OFFER_INTELLIGENCE", 5, false, null, null, threadId, null);
            } catch (Exception e) {
                log.debug("Career memory capture failed for offer {}: {}", saved.getId(), e.toString());
            }
            return saved;
        } catch (Exception e) {
            log.warn("OFFER_INTEL capture failed user={} thread={}: {}", userId, threadId, e.toString());
            return null;
        }
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static BigDecimal asBigDecimal(Object v) {
        if (v == null) return null;
        try {
            if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
            return new BigDecimal(v.toString().trim());
        } catch (Exception e) {
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
        if (v == null) return null;
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            return null;
        }
    }
}
