package ai.careerpilot.review.api;

import ai.careerpilot.domain.ApplicationPackage;
import ai.careerpilot.repo.ApplicationPackageRepository;
import ai.careerpilot.review.ApplicationReviewPipeline;
import ai.careerpilot.review.api.dto.ApplicationReviewDtos.*;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7.12 — authenticated human surface over the AI Review Pipeline. Every read/run is scoped to the
 * caller via the owning package's {@code userId} (the app's manual multi-tenant check) — a package id
 * belonging to another user reads as 404. The pipeline ships dark, so with stock flags reads 404 and
 * {@code POST /run} returns 404. {@code POST /run} performs a review only — it NEVER submits an application.
 */
@RestController
@RequestMapping("/api/application-review")
public class ApplicationReviewController {

    private final ApplicationReviewPipeline pipeline;
    private final ApplicationPackageRepository packages;

    public ApplicationReviewController(ApplicationReviewPipeline pipeline, ApplicationPackageRepository packages) {
        this.pipeline = pipeline;
        this.packages = packages;
    }

    /** Latest AI review for a package. 404 when never reviewed or owned by another user. */
    @GetMapping("/{packageId}")
    public ResponseEntity<ReviewResponse> get(AuthenticatedUser user, @PathVariable UUID packageId) {
        if (notOwned(user, packageId)) return ResponseEntity.notFound().build();
        return pipeline.latest(packageId).map(r -> ResponseEntity.ok(ReviewResponse.from(r)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Immutable review history (append-only) for a package. */
    @GetMapping("/{packageId}/history")
    public ResponseEntity<List<ReviewHistoryResponse>> history(AuthenticatedUser user, @PathVariable UUID packageId) {
        if (notOwned(user, packageId)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(pipeline.history(packageId).stream().map(ReviewHistoryResponse::from).toList());
    }

    /** The quality/verdict summary for a package's latest review. */
    @GetMapping("/{packageId}/quality")
    public ResponseEntity<QualityResponse> quality(AuthenticatedUser user, @PathVariable UUID packageId) {
        if (notOwned(user, packageId)) return ResponseEntity.notFound().build();
        return pipeline.latest(packageId).map(r -> ResponseEntity.ok(QualityResponse.from(r)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Run the review pipeline over a package. Review only — never submits. 404 when disabled / not owned. */
    @PostMapping("/{packageId}/run")
    public ResponseEntity<ReviewResponse> run(AuthenticatedUser user, @PathVariable UUID packageId) {
        if (notOwned(user, packageId)) return ResponseEntity.notFound().build();
        return pipeline.review(packageId, null).map(r -> ResponseEntity.ok(ReviewResponse.from(r)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private boolean notOwned(AuthenticatedUser user, UUID packageId) {
        Optional<ApplicationPackage> pkg = packages.findById(packageId);
        return pkg.isEmpty() || !Objects.equals(pkg.get().getUserId(), user.userId());
    }
}
