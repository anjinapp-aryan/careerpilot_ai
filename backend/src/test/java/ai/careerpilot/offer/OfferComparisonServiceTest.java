package ai.careerpilot.offer;

import ai.careerpilot.repo.OfferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Deterministic math assertions — no LLM involved, pure arithmetic over persisted Offer rows. */
class OfferComparisonServiceTest {

    private OfferRepository repo;
    private OfferComparisonService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        repo = mock(OfferRepository.class);
        service = new OfferComparisonService(repo);
        userId = UUID.randomUUID();
    }

    @Test
    void computesTotalCompAndDeltaFromHighest() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Offer offerA = Offer.builder().id(id1).userId(userId).companyName("Acme")
                .baseSalary(new BigDecimal("150000")).bonus(new BigDecimal("10000"))
                .rsuValue(new BigDecimal("40000")).joiningBonus(new BigDecimal("5000")).build();
        Offer offerB = Offer.builder().id(id2).userId(userId).companyName("Globex")
                .baseSalary(new BigDecimal("160000")).bonus(new BigDecimal("15000"))
                .rsuValue(new BigDecimal("60000")).joiningBonus(BigDecimal.ZERO).build();
        when(repo.findByUserIdAndIdIn(userId, List.of(id1, id2))).thenReturn(List.of(offerA, offerB));

        var result = service.compare(userId, List.of(id1, id2));

        assertEquals(2, result.rows().size());
        assertEquals(id2, result.highestOfferId());

        var rowA = result.rows().stream().filter(r -> r.offerId().equals(id1)).findFirst().orElseThrow();
        var rowB = result.rows().stream().filter(r -> r.offerId().equals(id2)).findFirst().orElseThrow();

        assertEquals(new BigDecimal("205000"), rowA.components().totalComp()); // 150000+10000+40000+5000
        assertEquals(new BigDecimal("235000"), rowB.components().totalComp()); // 160000+15000+60000+0
        assertEquals(new BigDecimal("30000"), rowA.deltaFromHighest());
        assertEquals(BigDecimal.ZERO, rowB.deltaFromHighest());
    }

    @Test
    void treatsNullComponentsAsZero() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Offer offerA = Offer.builder().id(id1).userId(userId).baseSalary(new BigDecimal("100000")).build();
        Offer offerB = Offer.builder().id(id2).userId(userId).baseSalary(new BigDecimal("120000"))
                .bonus(new BigDecimal("5000")).build();
        when(repo.findByUserIdAndIdIn(userId, List.of(id1, id2))).thenReturn(List.of(offerA, offerB));

        var result = service.compare(userId, List.of(id1, id2));

        var rowA = result.rows().stream().filter(r -> r.offerId().equals(id1)).findFirst().orElseThrow();
        assertEquals(new BigDecimal("100000"), rowA.components().totalComp());
    }

    @Test
    void returnsEmptyResultWhenFewerThanTwoOffersResolve() {
        UUID id1 = UUID.randomUUID();
        when(repo.findByUserIdAndIdIn(userId, List.of(id1))).thenReturn(List.of(
                Offer.builder().id(id1).userId(userId).build()));

        var result = service.compare(userId, List.of(id1));

        assertTrue(result.rows().isEmpty());
        assertNull(result.highestOfferId());
    }

    @Test
    void returnsEmptyResultForNullOrEmptyIds() {
        assertTrue(service.compare(userId, null).rows().isEmpty());
        assertTrue(service.compare(userId, List.of()).rows().isEmpty());
    }

    @Test
    void returnsEmptyResultForNullUser() {
        assertTrue(service.compare(null, List.of(UUID.randomUUID(), UUID.randomUUID())).rows().isEmpty());
    }
}
