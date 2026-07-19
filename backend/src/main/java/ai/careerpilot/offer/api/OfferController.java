package ai.careerpilot.offer.api;

import ai.careerpilot.offer.Offer;
import ai.careerpilot.offer.OfferComparisonService;
import ai.careerpilot.offer.OfferComparisonService.ComparisonResult;
import ai.careerpilot.repo.OfferRepository;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Gap B — Offer Intelligence & Salary Negotiation. Per-user data, so every endpoint goes through
 * the normal JWT-authenticated path (not added to SecurityConfig's permitAll list — see the
 * class-level note in SecurityConfig for what IS permitAll, e.g. diagnostics).
 *
 * <p>Ships dark behind {@code offer.intelligence.enabled}: reads degrade gracefully (empty list /
 * empty comparison, not 404) when the flag is off, matching the Phase 3B "dark-flag endpoints must
 * degrade gracefully" convention (see e.g. {@code ApplicationSubmissionController}); writes are
 * refused with a 409 {@code {"status":"NOT_ENABLED"}} body, same shape as
 * {@code WorkflowController.seed}.
 */
@RestController
@RequestMapping("/api/offers")
public class OfferController {

    private final OfferRepository offers;
    private final OfferComparisonService comparison;
    private final boolean enabled;

    public OfferController(OfferRepository offers, OfferComparisonService comparison,
                           @Value("${offer.intelligence.enabled:false}") boolean enabled) {
        this.offers = offers;
        this.comparison = comparison;
        this.enabled = enabled;
    }

    /** The current user's offers, newest first. Empty list (not an error) when disabled. */
    @GetMapping
    public List<Offer> list(AuthenticatedUser user) {
        if (!enabled) return List.of();
        return offers.findByUserIdOrderByCreatedAtDesc(user.userId());
    }

    @GetMapping("/{id}")
    public Offer get(AuthenticatedUser user, @PathVariable UUID id) {
        if (!enabled) throw new NoSuchElementException("offer intelligence is not enabled");
        Offer offer = offers.findById(id).orElseThrow(() -> new NoSuchElementException("offer not found"));
        if (!offer.getUserId().equals(user.userId())) throw new SecurityException("forbidden");
        return offer;
    }

    /** Manual offer entry. Refused (409) while {@code offer.intelligence.enabled} is off. */
    @PostMapping
    public ResponseEntity<?> create(AuthenticatedUser user, @RequestBody ManualOfferRequest req) {
        if (!enabled) {
            return ResponseEntity.status(409).body(Map.of("status", "NOT_ENABLED"));
        }
        Offer offer = Offer.builder()
                .userId(user.userId())
                .jobId(req.jobId())
                .companyName(req.companyName())
                .baseSalary(req.baseSalary())
                .bonus(req.bonus())
                .rsuValue(req.rsuValue())
                .equityDescription(req.equityDescription())
                .benefitsSummary(req.benefitsSummary())
                .joiningBonus(req.joiningBonus())
                .currency(req.currency())
                .source("MANUAL")
                .build();
        return ResponseEntity.ok(offers.save(offer));
    }

    /** Deterministic offer-vs-offer diff — no LLM call. Empty result when disabled or &lt;2 ids resolve. */
    @GetMapping("/compare")
    public ComparisonResult compare(AuthenticatedUser user, @RequestParam List<UUID> ids) {
        if (!enabled) return new ComparisonResult(List.of(), null);
        return comparison.compare(user.userId(), ids);
    }

    /** Body for {@code POST /api/offers} — manual entry. */
    public record ManualOfferRequest(UUID jobId, String companyName, BigDecimal baseSalary, BigDecimal bonus,
                                     BigDecimal rsuValue, String equityDescription, String benefitsSummary,
                                     BigDecimal joiningBonus, String currency) {}
}
