package ai.careerpilot.offer;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Entity test — Lombok builder/getters/setters wiring, same style as other entity tests in this repo. */
class OfferTest {

    @Test
    void builderPopulatesAllFields() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();

        Offer offer = Offer.builder()
                .id(id).userId(userId).jobId(jobId)
                .companyName("Acme").baseSalary(new BigDecimal("150000"))
                .bonus(new BigDecimal("10000")).rsuValue(new BigDecimal("50000"))
                .equityDescription("4yr vest").benefitsSummary("Health, dental")
                .joiningBonus(new BigDecimal("5000")).currency("USD")
                .source("MANUAL")
                .marketP25(new BigDecimal("120000")).marketP50(new BigDecimal("150000"))
                .marketP75(new BigDecimal("180000")).marketP90(new BigDecimal("210000"))
                .negotiationStrategy("- Ask for more equity")
                .leveragePoints("[\"competing offer\"]")
                .sourceThreadId("thread-1")
                .createdAt(now).updatedAt(now)
                .build();

        assertEquals(id, offer.getId());
        assertEquals(userId, offer.getUserId());
        assertEquals(jobId, offer.getJobId());
        assertEquals("Acme", offer.getCompanyName());
        assertEquals(new BigDecimal("150000"), offer.getBaseSalary());
        assertEquals("MANUAL", offer.getSource());
        assertEquals("USD", offer.getCurrency());
        assertEquals(new BigDecimal("210000"), offer.getMarketP90());
        assertEquals("thread-1", offer.getSourceThreadId());
    }

    @Test
    void defaultSourceIsManualWhenUnset() {
        Offer offer = Offer.builder().userId(UUID.randomUUID()).build();
        assertEquals("MANUAL", offer.getSource());
    }

    @Test
    void settersMutateState() {
        Offer offer = new Offer();
        offer.setCompanyName("Globex");
        offer.setBaseSalary(new BigDecimal("100000"));
        assertEquals("Globex", offer.getCompanyName());
        assertEquals(new BigDecimal("100000"), offer.getBaseSalary());
    }
}
