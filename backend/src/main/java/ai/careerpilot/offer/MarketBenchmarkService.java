package ai.careerpilot.offer;

import ai.careerpilot.repo.OfferRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Gap B — thin read wrapper exposing the market percentiles already persisted on an {@link Offer}
 * row by {@link OfferAnalysisService}. No new computation happens here; this exists purely so
 * callers (Copilot handler, controller) don't reach into the entity directly.
 */
@Service
public class MarketBenchmarkService {

    private final OfferRepository offers;

    public MarketBenchmarkService(OfferRepository offers) {
        this.offers = offers;
    }

    public record MarketBenchmark(String currency, BigDecimal p25, BigDecimal p50, BigDecimal p75, BigDecimal p90) {}

    public Optional<MarketBenchmark> forOffer(UUID userId, UUID offerId) {
        return offers.findById(offerId)
                .filter(o -> userId != null && userId.equals(o.getUserId()))
                .filter(o -> o.getMarketP25() != null || o.getMarketP50() != null
                        || o.getMarketP75() != null || o.getMarketP90() != null)
                .map(o -> new MarketBenchmark(o.getCurrency(), o.getMarketP25(), o.getMarketP50(),
                        o.getMarketP75(), o.getMarketP90()));
    }
}
