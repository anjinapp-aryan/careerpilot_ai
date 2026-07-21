package ai.careerpilot.offer;

import ai.careerpilot.repo.OfferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Service tests — parses (or tolerates malformed) salary_insights from a WorkflowRun's state
 * blob. The agent-service JSON is not guaranteed structured, so malformed/missing input must
 * never throw.
 */
class OfferAnalysisServiceTest {

    private OfferRepository repo;

    @BeforeEach
    void setUp() {
        repo = mock(OfferRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void doesNothingWhenDisabled() {
        OfferAnalysisService service = new OfferAnalysisService(repo, mock(ai.careerpilot.memory.CareerMemoryService.class),false);
        UUID userId = UUID.randomUUID();
        Map<String, Object> state = Map.of("salary_insights", Map.of("currency", "USD", "p50", 150000));

        Offer result = service.captureFromWorkflow(userId, "thread-1", state);

        assertNull(result);
        verifyNoInteractions(repo);
    }

    @Test
    void capturesWellFormedSalaryInsights() {
        OfferAnalysisService service = new OfferAnalysisService(repo, mock(ai.careerpilot.memory.CareerMemoryService.class),true);
        UUID userId = UUID.randomUUID();
        when(repo.findByUserIdAndSourceThreadId(userId, "thread-1")).thenReturn(Optional.empty());

        Map<String, Object> insights = Map.of(
                "currency", "USD",
                "p25", 120000, "p50", 150000, "p75", 180000, "p90", 210000,
                "negotiation_strategy", List.of("Ask for more equity", "Highlight competing offer"),
                "leverage_points", List.of("competing offer"));
        Map<String, Object> state = Map.of("salary_insights", insights, "target_role", "Staff Engineer");

        Offer result = service.captureFromWorkflow(userId, "thread-1", state);

        assertNotNull(result);
        assertEquals("USD", result.getCurrency());
        assertEquals(0, new BigDecimal("150000").compareTo(result.getMarketP50()));
        assertEquals("SALARY_INTELLIGENCE_AGENT", result.getSource());
        assertNotNull(result.getNegotiationStrategy());
        assertTrue(result.getNegotiationStrategy().contains("Ask for more equity"));
        verify(repo).save(any(Offer.class));
    }

    @Test
    void upsertsExistingOfferForSameThread() {
        OfferAnalysisService service = new OfferAnalysisService(repo, mock(ai.careerpilot.memory.CareerMemoryService.class),true);
        UUID userId = UUID.randomUUID();
        Offer existing = Offer.builder().id(UUID.randomUUID()).userId(userId).sourceThreadId("thread-1")
                .source("SALARY_INTELLIGENCE_AGENT").build();
        when(repo.findByUserIdAndSourceThreadId(userId, "thread-1")).thenReturn(Optional.of(existing));

        Map<String, Object> insights = Map.of("currency", "USD", "p50", 160000);
        Map<String, Object> state = Map.of("salary_insights", insights);

        Offer result = service.captureFromWorkflow(userId, "thread-1", state);

        assertSame(existing, result);
        assertEquals(0, new BigDecimal("160000").compareTo(result.getMarketP50()));
    }

    @Test
    void returnsNullWhenSalaryInsightsKeyMissing() {
        OfferAnalysisService service = new OfferAnalysisService(repo, mock(ai.careerpilot.memory.CareerMemoryService.class),true);
        Offer result = service.captureFromWorkflow(UUID.randomUUID(), "thread-1", Map.of("other_key", "value"));
        assertNull(result);
        verify(repo, never()).save(any());
    }

    @Test
    void returnsNullWhenSalaryInsightsIsEmptyMap() {
        OfferAnalysisService service = new OfferAnalysisService(repo, mock(ai.careerpilot.memory.CareerMemoryService.class),true);
        Offer result = service.captureFromWorkflow(UUID.randomUUID(), "thread-1", Map.of("salary_insights", Map.of()));
        assertNull(result);
    }

    @Test
    void returnsNullWhenSalaryInsightsIsWrongType() {
        OfferAnalysisService service = new OfferAnalysisService(repo, mock(ai.careerpilot.memory.CareerMemoryService.class),true);
        Map<String, Object> state = Map.of("salary_insights", "not-a-map");
        Offer result = service.captureFromWorkflow(UUID.randomUUID(), "thread-1", state);
        assertNull(result);
    }

    @Test
    void toleratesMalformedNumericFields() {
        OfferAnalysisService service = new OfferAnalysisService(repo, mock(ai.careerpilot.memory.CareerMemoryService.class),true);
        UUID userId = UUID.randomUUID();
        when(repo.findByUserIdAndSourceThreadId(any(), any())).thenReturn(Optional.empty());

        Map<String, Object> insights = new java.util.HashMap<>();
        insights.put("currency", "USD");
        insights.put("p50", "not-a-number");
        insights.put("negotiation_strategy", "not-a-list");
        Map<String, Object> state = Map.of("salary_insights", insights);

        Offer result = service.captureFromWorkflow(userId, "thread-1", state);

        assertNotNull(result);
        assertNull(result.getMarketP50());
        assertNull(result.getNegotiationStrategy());
    }

    @Test
    void returnsNullOnNullState() {
        OfferAnalysisService service = new OfferAnalysisService(repo, mock(ai.careerpilot.memory.CareerMemoryService.class),true);
        assertNull(service.captureFromWorkflow(UUID.randomUUID(), "thread-1", null));
    }

    @Test
    void neverThrowsWhenRepositorySaveFails() {
        OfferRepository failingRepo = mock(OfferRepository.class);
        when(failingRepo.findByUserIdAndSourceThreadId(any(), any())).thenReturn(Optional.empty());
        when(failingRepo.save(any())).thenThrow(new RuntimeException("db down"));
        OfferAnalysisService service = new OfferAnalysisService(failingRepo, mock(ai.careerpilot.memory.CareerMemoryService.class),true);

        Map<String, Object> state = Map.of("salary_insights", Map.of("currency", "USD", "p50", 100000));

        assertDoesNotThrow(() -> {
            Offer result = service.captureFromWorkflow(UUID.randomUUID(), "thread-1", state);
            assertNull(result);
        });
    }
}
