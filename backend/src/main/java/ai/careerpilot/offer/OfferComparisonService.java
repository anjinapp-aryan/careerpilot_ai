package ai.careerpilot.offer;

import ai.careerpilot.repo.OfferRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Gap B — deterministic offer-vs-offer diff. No LLM call: pure arithmetic over the persisted
 * {@link Offer} rows (total-comp = base + bonus + rsu + joining bonus, each component's delta
 * from the highest offer). This is informational only — never presented as legal/financial
 * advice; the Copilot handler layered on top ({@code OfferComparisonHandler}) is responsible for
 * keeping that framing in its system prompt.
 */
@Service
public class OfferComparisonService {

    private final OfferRepository offers;

    public OfferComparisonService(OfferRepository offers) {
        this.offers = offers;
    }

    public record ComponentBreakdown(BigDecimal baseSalary, BigDecimal bonus, BigDecimal rsuValue,
                                     BigDecimal joiningBonus, BigDecimal totalComp) {}

    public record OfferComparisonRow(UUID offerId, String companyName, String currency,
                                     ComponentBreakdown components, BigDecimal deltaFromHighest) {}

    public record ComparisonResult(List<OfferComparisonRow> rows, UUID highestOfferId) {}

    /**
     * Compares 2+ offers belonging to {@code userId}. Offers owned by a different user, or ids
     * that don't resolve, are silently excluded (ownership enforced here, not just at the
     * controller). Returns an empty result if fewer than 2 offers resolve.
     */
    public ComparisonResult compare(UUID userId, List<UUID> offerIds) {
        if (userId == null || offerIds == null || offerIds.isEmpty()) {
            return new ComparisonResult(List.of(), null);
        }
        List<Offer> found = offers.findByUserIdAndIdIn(userId, offerIds);
        if (found.size() < 2) {
            return new ComparisonResult(List.of(), null);
        }

        List<OfferComparisonRow> rows = found.stream()
                .map(o -> new OfferComparisonRow(o.getId(), o.getCompanyName(), o.getCurrency(),
                        breakdown(o), null))
                .toList();

        BigDecimal highest = rows.stream().map(r -> r.components().totalComp())
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        UUID highestId = rows.stream()
                .filter(r -> r.components().totalComp().compareTo(highest) == 0)
                .map(OfferComparisonRow::offerId).findFirst().orElse(null);

        List<OfferComparisonRow> withDeltas = rows.stream()
                .map(r -> new OfferComparisonRow(r.offerId(), r.companyName(), r.currency(),
                        r.components(), highest.subtract(r.components().totalComp())))
                .toList();

        return new ComparisonResult(withDeltas, highestId);
    }

    private ComponentBreakdown breakdown(Offer o) {
        BigDecimal base = nvl(o.getBaseSalary());
        BigDecimal bonus = nvl(o.getBonus());
        BigDecimal rsu = nvl(o.getRsuValue());
        BigDecimal joining = nvl(o.getJoiningBonus());
        return new ComponentBreakdown(base, bonus, rsu, joining, base.add(bonus).add(rsu).add(joining));
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
