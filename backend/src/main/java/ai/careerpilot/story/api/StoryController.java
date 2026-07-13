package ai.careerpilot.story.api;

import ai.careerpilot.domain.StarStory;
import ai.careerpilot.domain.StoryRecommendation;
import ai.careerpilot.repo.StarStoryRepository;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.story.StarStoryEngine;
import ai.careerpilot.story.StoryStatus;
import ai.careerpilot.story.StorySource;
import ai.careerpilot.story.StoryType;
import ai.careerpilot.story.api.dto.StoryDtos.*;
import ai.careerpilot.story.engine.StorySearchEngine;
import ai.careerpilot.story.engine.StorySimilarityEngine;
import ai.careerpilot.story.engine.StoryVersionManager;
import ai.careerpilot.story.recommender.StoryRecommendationEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 7.15 — authenticated read/write surface over STAR Story Intelligence. Every read/write is
 * scoped to the caller's {@code userId} (the app's manual multi-tenant check). Ships dark — with
 * {@code story.engine.enabled=false} every endpoint returns 404 or a NOT_ENABLED 409 (mutating
 * endpoints), so the frontend's {@code nullOn404} client pattern works unmodified.
 */
@RestController
@RequestMapping("/api/story")
public class StoryController {

    private final StarStoryEngine engine;
    private final StarStoryRepository stories;
    private final StoryVersionManager versionManager;
    private final StorySimilarityEngine similarity;
    private final StorySearchEngine search;
    private final StoryRecommendationEngine recommender;

    public StoryController(StarStoryEngine engine, StarStoryRepository stories,
                           StoryVersionManager versionManager, StorySimilarityEngine similarity,
                           StorySearchEngine search, StoryRecommendationEngine recommender) {
        this.engine = engine;
        this.stories = stories;
        this.versionManager = versionManager;
        this.similarity = similarity;
        this.search = search;
        this.recommender = recommender;
    }

    @GetMapping
    public ResponseEntity<List<StorySummary>> list(AuthenticatedUser user) {
        if (!engine.isEnabled()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(engine.list(user.userId()).stream().map(StorySummary::from).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<StoryResponse> get(AuthenticatedUser user, @PathVariable UUID id) {
        return engine.get(user.userId(), id).map(s -> ResponseEntity.ok(StoryResponse.from(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/categories")
    public ResponseEntity<List<StoryType>> categories() {
        if (!engine.isEnabled()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(engine.categories());
    }

    @GetMapping("/analytics")
    public ResponseEntity<AnalyticsResponse> analytics(AuthenticatedUser user) {
        return engine.analytics(user.userId()).map(a -> ResponseEntity.ok(AnalyticsResponse.from(a)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<VersionResponse>> history(AuthenticatedUser user, @PathVariable UUID id) {
        return owned(user, id).map(s -> ResponseEntity.ok(
                        versionManager.history(id).stream().map(VersionResponse::from).toList()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/similar/{id}")
    public ResponseEntity<List<StorySimilarityEngine.SimilarStory>> similar(AuthenticatedUser user, @PathVariable UUID id) {
        if (!similarity.isEnabled()) return ResponseEntity.notFound().build();
        return owned(user, id).map(s -> ResponseEntity.ok(similarity.similarTo(user.userId(), id, 10)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/search")
    public ResponseEntity<List<StorySummary>> search(AuthenticatedUser user, @RequestParam(required = false) String q) {
        if (!search.isEnabled()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(search.search(user.userId(), q, 25).stream()
                .map(h -> StorySummary.from(h.story())).toList());
    }

    @PostMapping("/extract")
    public ResponseEntity<Map<String, Object>> extract(AuthenticatedUser user) {
        if (!engine.isEnabled()) return conflict();
        // Extraction on its own is a read-only preview; generation is what persists a story.
        return ResponseEntity.ok(Map.of("status", "OK", "note", "call POST /api/story/generate to draft a story"));
    }

    @PostMapping("/generate")
    public ResponseEntity<StoryResponse> generate(AuthenticatedUser user, @RequestBody GenerateRequest req) {
        if (!engine.isEnabled()) return conflict();
        return engine.generate(user.userId(), req.storyType(), req.title(), req.hint())
                .map(s -> ResponseEntity.ok(StoryResponse.from(s)))
                .orElseGet(() -> ResponseEntity.status(409).build());
    }

    @PostMapping("/create")
    public ResponseEntity<StoryResponse> create(AuthenticatedUser user, @RequestBody ManualCreateRequest req) {
        if (!engine.isEnabled()) return conflict();
        StarStory story = StarStory.builder()
                .title(req.title() == null || req.title().isBlank() ? "Untitled Story" : req.title())
                .storyType(req.storyType() == null ? StoryType.SUCCESS : req.storyType())
                .status(StoryStatus.DRAFT)
                .source(StorySource.MANUAL)
                .situation(req.situation()).task(req.task()).action(req.action()).result(req.result())
                .reflection(req.reflection()).lessonsLearned(req.lessonsLearned())
                .skillsUsed(req.skillsUsed()).technologiesUsed(req.technologiesUsed())
                .businessImpact(req.businessImpact()).evidence(req.evidence())
                .confidenceScore(req.confidenceScore())
                .build();
        return engine.createManual(user.userId(), story)
                .map(s -> ResponseEntity.ok(StoryResponse.from(s)))
                .orElseGet(() -> ResponseEntity.status(409).build());
    }

    @PostMapping("/{id}/improve")
    public ResponseEntity<StoryResponse> improve(AuthenticatedUser user, @PathVariable UUID id,
                                                 @RequestBody(required = false) ImproveRequest req) {
        if (!engine.isEnabled()) return conflict();
        String feedback = req == null ? null : req.feedback();
        return engine.improve(user.userId(), id, feedback)
                .map(s -> ResponseEntity.ok(StoryResponse.from(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/rate")
    public ResponseEntity<StoryResponse> rate(AuthenticatedUser user, @PathVariable UUID id, @RequestBody RateRequest req) {
        if (!engine.isEnabled()) return conflict();
        return engine.rate(user.userId(), id, req == null ? null : req.confidenceScore())
                .map(s -> ResponseEntity.ok(StoryResponse.from(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/use")
    public ResponseEntity<UsageResponse> recordUsage(AuthenticatedUser user, @RequestBody UsageRequest req) {
        if (!engine.isEnabled()) return conflict();
        return engine.recordUsage(user.userId(), req.storyId(), req.companyName(), req.targetRole(),
                        req.interviewRound(), req.question(), req.outcome())
                .map(u -> ResponseEntity.ok(UsageResponse.from(u)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/recommend")
    public ResponseEntity<List<RecommendationResponse>> recommend(AuthenticatedUser user, @RequestBody RecommendRequest req) {
        if (!recommender.isEnabled()) return conflict();
        List<StoryRecommendation> recs = recommender.recommend(user.userId(), req.companyName(), req.targetRole(), req.question(), 5);
        return ResponseEntity.ok(recs.stream()
                .map(r -> RecommendationResponse.from(r, titleOf(user.userId(), r.getStarStoryId())))
                .toList());
    }

    // ── helpers ──

    private java.util.Optional<StarStory> owned(AuthenticatedUser user, UUID id) {
        return engine.get(user.userId(), id);
    }

    private String titleOf(UUID userId, UUID storyId) {
        return stories.findByIdAndUserId(storyId, userId).map(StarStory::getTitle).orElse(null);
    }

    private static <T> ResponseEntity<T> conflict() {
        return ResponseEntity.status(409).build();
    }
}
