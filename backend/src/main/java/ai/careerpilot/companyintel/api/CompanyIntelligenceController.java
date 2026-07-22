package ai.careerpilot.companyintel.api;

import ai.careerpilot.companyintel.*;
import ai.careerpilot.companyintel.api.dto.CompanyIntelDtos.*;
import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.domain.CompanyRelationship;
import ai.careerpilot.domain.CompanyTimelineEvent;
import ai.careerpilot.companyintel.analyzer.HiringVelocityAnalyzer;
import ai.careerpilot.repo.CompanyKnowledgeRepository;
import ai.careerpilot.repo.CompanyKnowledgeVersionRepository;
import ai.careerpilot.repo.CompanyRelationshipRepository;
import ai.careerpilot.repo.CompanyTimelineEventRepository;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 7.13 — authenticated read surface over the Company Knowledge Graph. Every read is scoped to
 * the caller's {@code userId} (the app's manual multi-tenant check): another user's company id reads
 * as 404. Ships dark — with {@code company.knowledge.enabled=false} every endpoint returns 404, so
 * the UI degrades gracefully. Rollback is the only write, and it only re-heads existing history.
 */
@RestController
@RequestMapping("/api/company")
public class CompanyIntelligenceController {

    private final CompanyKnowledgeService knowledge;
    private final CompanyKnowledgeRepository companies;
    private final CompanyRelationshipRepository relationships;
    private final CompanyTimelineEventRepository timelineEvents;
    private final CompanyKnowledgeVersionRepository versions;
    private final KnowledgeVersionManager versionManager;
    private final CompanyTimelineService timeline;
    private final CompanySimilarityService similarity;
    private final CompanyInsightGenerator insights;
    private final KnowledgeAggregator aggregator;
    private final CandidateFitService candidateFit;
    private final CompanyInterviewIntelligenceService interviewIntelligence;
    private final HiringVelocityAnalyzer hiringVelocity;
    private final boolean analyticsEnabled;

    public CompanyIntelligenceController(CompanyKnowledgeService knowledge,
                                         CompanyKnowledgeRepository companies,
                                         CompanyRelationshipRepository relationships,
                                         CompanyTimelineEventRepository timelineEvents,
                                         CompanyKnowledgeVersionRepository versions,
                                         KnowledgeVersionManager versionManager,
                                         CompanyTimelineService timeline,
                                         CompanySimilarityService similarity,
                                         CompanyInsightGenerator insights,
                                         KnowledgeAggregator aggregator,
                                         CandidateFitService candidateFit,
                                         CompanyInterviewIntelligenceService interviewIntelligence,
                                         HiringVelocityAnalyzer hiringVelocity,
                                         @Value("${company.analytics.enabled:false}") boolean analyticsEnabled) {
        this.knowledge = knowledge;
        this.companies = companies;
        this.relationships = relationships;
        this.timelineEvents = timelineEvents;
        this.versions = versions;
        this.versionManager = versionManager;
        this.timeline = timeline;
        this.similarity = similarity;
        this.insights = insights;
        this.aggregator = aggregator;
        this.candidateFit = candidateFit;
        this.interviewIntelligence = interviewIntelligence;
        this.hiringVelocity = hiringVelocity;
        this.analyticsEnabled = analyticsEnabled;
    }

    /** Search this user's known companies (blank query lists all, most recently updated first). */
    @GetMapping("/search")
    public ResponseEntity<List<CompanySummary>> search(AuthenticatedUser user,
                                                       @RequestParam(required = false) String q) {
        if (!knowledge.isEnabled()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(knowledge.search(user.userId(), q).stream().map(CompanySummary::from).toList());
    }

    /** Full company view: scores + explanations + knowledge sections + deterministic insights. */
    @GetMapping("/{id}")
    public ResponseEntity<CompanyResponse> get(AuthenticatedUser user, @PathVariable UUID id) {
        return owned(user, id).map(c -> ResponseEntity.ok(toResponse(c)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/timeline")
    public ResponseEntity<List<TimelineEventResponse>> timeline(AuthenticatedUser user, @PathVariable UUID id) {
        return owned(user, id).map(c -> ResponseEntity.ok(
                        timeline.timeline(id).stream().map(TimelineEventResponse::from).toList()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The raw knowledge section map. */
    @GetMapping("/{id}/knowledge")
    public ResponseEntity<Map<String, String>> knowledgeSections(AuthenticatedUser user, @PathVariable UUID id) {
        return owned(user, id).map(c -> ResponseEntity.ok(knowledge.sections(c)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Immutable version history (append-only, never rewritten). */
    @GetMapping("/{id}/history")
    public ResponseEntity<List<VersionResponse>> history(AuthenticatedUser user, @PathVariable UUID id) {
        return owned(user, id).map(c -> ResponseEntity.ok(
                        versionManager.history(id).stream().map(VersionResponse::from).toList()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Section-level diff between two stored versions. */
    @GetMapping("/{id}/history/compare")
    public ResponseEntity<Map<String, List<String>>> compareVersions(AuthenticatedUser user, @PathVariable UUID id,
                                                                     @RequestParam int from, @RequestParam int to) {
        return owned(user, id).map(c -> ResponseEntity.ok(versionManager.compare(id, from, to)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Restore an older snapshot as a NEW head version (history preserved). */
    @PostMapping("/{id}/history/rollback")
    public ResponseEntity<CompanyResponse> rollback(AuthenticatedUser user, @PathVariable UUID id,
                                                    @RequestParam int to) {
        return knowledge.rollback(user.userId(), id, to).map(c -> ResponseEntity.ok(toResponse(c)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/similar")
    public ResponseEntity<List<CompanySimilarityService.SimilarCompany>> similar(AuthenticatedUser user,
                                                                                 @PathVariable UUID id) {
        if (!similarity.isEnabled()) return ResponseEntity.notFound().build();
        return owned(user, id).map(c -> ResponseEntity.ok(similarity.similarTo(user.userId(), id, 10)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/technology")
    public ResponseEntity<List<RelationshipResponse>> technology(AuthenticatedUser user, @PathVariable UUID id) {
        return edges(user, id, CompanyRelationship.TYPE_TECHNOLOGY);
    }

    @GetMapping("/{id}/skills")
    public ResponseEntity<List<RelationshipResponse>> skills(AuthenticatedUser user, @PathVariable UUID id) {
        return edges(user, id, CompanyRelationship.TYPE_SKILL);
    }

    @GetMapping("/{id}/culture")
    public ResponseEntity<Map<String, String>> culture(AuthenticatedUser user, @PathVariable UUID id) {
        return owned(user, id).map(c -> {
            Map<String, String> sections = knowledge.sections(c);
            Map<String, String> out = new LinkedHashMap<>();
            for (KnowledgeSection s : List.of(KnowledgeSection.CULTURE, KnowledgeSection.REPUTATION,
                    KnowledgeSection.BENEFITS, KnowledgeSection.LEADERSHIP)) {
                if (sections.containsKey(s.key())) out.put(s.key(), sections.get(s.key()));
            }
            return ResponseEntity.ok(out);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/interviews")
    public ResponseEntity<Map<String, String>> interviews(AuthenticatedUser user, @PathVariable UUID id) {
        return owned(user, id).map(c -> {
            Map<String, String> sections = knowledge.sections(c);
            Map<String, String> out = new LinkedHashMap<>();
            if (sections.containsKey(KnowledgeSection.INTERVIEW_PROCESS.key())) {
                out.put(KnowledgeSection.INTERVIEW_PROCESS.key(), sections.get(KnowledgeSection.INTERVIEW_PROCESS.key()));
            }
            out.put("interviewDifficulty", String.valueOf(c.getInterviewDifficulty()));
            return ResponseEntity.ok(out);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Compare two companies: summaries, similarity score, section-level knowledge diff. */
    @GetMapping("/compare")
    public ResponseEntity<CompareResponse> compare(AuthenticatedUser user,
                                                   @RequestParam UUID a, @RequestParam UUID b) {
        Optional<CompanyKnowledge> ka = owned(user, a);
        Optional<CompanyKnowledge> kb = owned(user, b);
        if (ka.isEmpty() || kb.isEmpty()) return ResponseEntity.notFound().build();
        int sim = CompanySimilarityService.similarity(edgesByType(a), edgesByType(b));
        Map<String, List<String>> diff = new LinkedHashMap<>();
        Map<String, String> sa = knowledge.sections(ka.get());
        Map<String, String> sb = knowledge.sections(kb.get());
        Set<String> keys = new LinkedHashSet<>(sa.keySet());
        keys.addAll(sb.keySet());
        for (String key : keys) diff.put(key, Arrays.asList(sa.get(key), sb.get(key)));
        return ResponseEntity.ok(new CompareResponse(CompanySummary.from(ka.get()), CompanySummary.from(kb.get()), sim, diff));
    }

    /** The user's whole knowledge graph: company nodes + typed edges. */
    @GetMapping("/graph")
    public ResponseEntity<GraphResponse> graph(AuthenticatedUser user) {
        if (!knowledge.isEnabled()) return ResponseEntity.notFound().build();
        List<CompanyKnowledge> nodes = companies.findByUserIdOrderByUpdatedAtDesc(user.userId());
        Map<String, List<RelationshipResponse>> edges = relationships.findByUserId(user.userId()).stream()
                .collect(Collectors.groupingBy(e -> e.getCompanyKnowledgeId().toString(),
                        Collectors.mapping(RelationshipResponse::from, Collectors.toList())));
        return ResponseEntity.ok(new GraphResponse(nodes.stream().map(CompanySummary::from).toList(), edges));
    }

    /** Aggregate statistics for dashboards. Additionally gated by company.analytics.enabled. */
    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics(AuthenticatedUser user) {
        if (!knowledge.isEnabled() || !analyticsEnabled) return ResponseEntity.notFound().build();
        UUID userId = user.userId();
        List<CompanyKnowledge> all = companies.findByUserIdOrderByUpdatedAtDesc(userId);
        List<CompanyKnowledge> top = all.stream()
                .sorted(Comparator.comparingInt(c -> c.getQualityScore() == null ? 0 : -c.getQualityScore()))
                .limit(10)
                .toList();
        List<CompanyRelationship> allEdges = relationships.findByUserId(userId);
        Map<String, Long> tech = distribution(allEdges, CompanyRelationship.TYPE_TECHNOLOGY, CompanyRelationship.TYPE_SKILL);
        Map<String, Long> industry = distribution(allEdges, CompanyRelationship.TYPE_INDUSTRY);
        return ResponseEntity.ok(new StatisticsResponse(
                all.size(), versions.countByUserId(userId), timelineEvents.countByUserId(userId), allEdges.size(),
                top.stream().map(CompanySummary::from).toList(),
                StatisticsResponse.topCounts(tech, 15), StatisticsResponse.topCounts(industry, 10)));
    }

    /**
     * Phase 7.17 — Candidate Fit unification. Keyed by {@code jobId} (not a company-knowledge id)
     * since several dimensions are per-JOB (salary/location/visa can differ job-to-job at the same
     * company). Reuses {@code JobScoring}'s persisted per-job breakdown + this company's existing
     * 10 scores + {@code CareerMemoryBooster} — no new scoring engine. 404 when the feature is dark
     * or the job doesn't exist.
     */
    @GetMapping("/fit")
    public ResponseEntity<Map<String, Object>> fit(AuthenticatedUser user, @RequestParam UUID jobId) {
        return candidateFit.explainFit(user.userId(), jobId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Phase 7.17.2 — real Interview/InterviewFeedback data aggregated per company. See {@link
     * CompanyInterviewIntelligenceService} for the exact honesty caveats (pass rate is a
     * self-assessment, not a confirmed employer decision; coding/behavioral rounds are not
     * distinguishable from the underlying data).
     */
    @GetMapping("/{id}/interview-intelligence")
    public ResponseEntity<Map<String, Object>> interviewIntelligence(AuthenticatedUser user, @PathVariable UUID id) {
        return owned(user, id)
                .flatMap(c -> interviewIntelligence.forCompany(user.userId(), c.getCompanyName()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Phase 7.17.3 — Hiring Intelligence, extended. Combines the existing {@code
     * HiringPatternAnalyzer}/{@code CompanyTrendAnalyzer} insight output (already generated by
     * {@link CompanyInsightGenerator}) with the new time-to-hire metric — no recomputation of what
     * already exists. Department/seasonal hiring report as {@code null} — see {@link
     * HiringVelocityAnalyzer}'s javadoc for why.
     */
    @GetMapping("/{id}/hiring-intelligence")
    public ResponseEntity<Map<String, Object>> hiringIntelligence(AuthenticatedUser user, @PathVariable UUID id) {
        return owned(user, id).map(c -> {
            List<CompanyTimelineEvent> events = timelineEvents.findByCompanyKnowledgeIdOrderByOccurredAtDesc(id);
            Map<String, Object> out = new LinkedHashMap<>(hiringVelocity.metrics(events));
            out.put("hiringProbability", c.getHiringProbability());
            out.put("insights", insights.generate(c).stream()
                    .filter(i -> "HIRING_PATTERN".equals(i.kind()) || "TREND".equals(i.kind()) || "HIRING_VELOCITY".equals(i.kind()))
                    .toList());
            return ResponseEntity.ok(out);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Phase 7.17.4 — Technology Intelligence taxonomy. Buckets this company's existing TECHNOLOGY/
     * SKILL graph edges into the taxonomy defined in {@link TechnologyTaxonomy} — a deterministic
     * keyword lookup, never AI inference. Popularity is the existing edge weight (reused, not
     * recomputed); growth/decline trends are not computed (no historical weight time-series exists).
     */
    @GetMapping("/{id}/technology-taxonomy")
    public ResponseEntity<Map<String, Object>> technologyTaxonomy(AuthenticatedUser user, @PathVariable UUID id) {
        return owned(user, id).map(c -> {
            List<CompanyRelationship> edges = relationships.findByCompanyKnowledgeId(id).stream()
                    .filter(e -> CompanyRelationship.TYPE_TECHNOLOGY.equals(e.getRelationType())
                            || CompanyRelationship.TYPE_SKILL.equals(e.getRelationType()))
                    .toList();
            Map<String, List<Map<String, Object>>> byCategory = new LinkedHashMap<>();
            for (String category : TechnologyTaxonomy.categories()) byCategory.put(category, new ArrayList<>());
            for (CompanyRelationship edge : edges) {
                String category = TechnologyTaxonomy.categorize(edge.getTarget());
                byCategory.get(category).add(Map.of("target", edge.getTarget(), "weight", edge.getWeight()));
            }
            byCategory.values().forEach(list ->
                    list.sort(Comparator.comparingInt((Map<String, Object> m) -> (int) m.get("weight")).reversed()));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("byCategory", byCategory);
            out.put("totalEdges", edges.size());
            out.put("growthDeclineNote", "not computed — CompanyRelationship stores only a current weight, no historical time series");
            return ResponseEntity.ok(out);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── helpers ──

    private Optional<CompanyKnowledge> owned(AuthenticatedUser user, UUID id) {
        return knowledge.get(user.userId(), id);
    }

    private CompanyResponse toResponse(CompanyKnowledge c) {
        return CompanyResponse.from(c, knowledge.sections(c),
                aggregator.parse(c.getScoreExplanations()), insights.generate(c));
    }

    private ResponseEntity<List<RelationshipResponse>> edges(AuthenticatedUser user, UUID id, String type) {
        return owned(user, id).map(c -> ResponseEntity.ok(
                        relationships.findByCompanyKnowledgeIdAndRelationType(id, type).stream()
                                .sorted(Comparator.comparingInt(CompanyRelationship::getWeight).reversed())
                                .map(RelationshipResponse::from).toList()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Set<String>> edgesByType(UUID companyKnowledgeId) {
        return relationships.findByCompanyKnowledgeId(companyKnowledgeId).stream()
                .collect(Collectors.groupingBy(CompanyRelationship::getRelationType,
                        Collectors.mapping(CompanyRelationship::getTarget, Collectors.toSet())));
    }

    private static Map<String, Long> distribution(List<CompanyRelationship> edges, String... types) {
        Set<String> wanted = Set.of(types);
        return edges.stream()
                .filter(e -> wanted.contains(e.getRelationType()))
                .collect(Collectors.groupingBy(CompanyRelationship::getTarget, Collectors.counting()));
    }
}
