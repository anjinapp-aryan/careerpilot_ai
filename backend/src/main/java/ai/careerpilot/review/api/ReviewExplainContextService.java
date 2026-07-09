package ai.careerpilot.review.api;

import ai.careerpilot.domain.ApplicationReview;
import ai.careerpilot.repo.ApplicationReviewRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Phase 7.12 — read-only snapshot of a user's recent AI reviews so the Copilot's review-explanation
 * skill grounds its answers (resume/ATS/company/learning/quality/verdict) in real rows instead of
 * hallucinating. Returns an empty list when the pipeline is dark (no reviews exist yet).
 */
@Service
public class ReviewExplainContextService {

    private static final int RECENT = 8;

    private final ApplicationReviewRepository reviews;

    public ReviewExplainContextService(ApplicationReviewRepository reviews) {
        this.reviews = reviews;
    }

    public ReviewExplainContext get(UUID userId) {
        List<ApplicationReview> recent = reviews.findByUserIdOrderByUpdatedAtDesc(userId)
                .stream().limit(RECENT).toList();
        return new ReviewExplainContext(recent);
    }

    public record ReviewExplainContext(List<ApplicationReview> recentReviews) {}
}
