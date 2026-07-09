package ai.careerpilot.companyintel;

import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.domain.CompanyRelationship;
import ai.careerpilot.repo.CompanyKnowledgeRepository;
import ai.careerpilot.repo.CompanyRelationshipRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 7.13 — finds similar companies inside the user's own knowledge graph, via weighted Jaccard
 * overlap of graph edges: technology 35%, skills 25%, industry 20%, roles 10%, locations 10%.
 * Deterministic, no LLM. Gated by {@code company.similarity.enabled} (default off).
 */
@Service
public class CompanySimilarityService {

    private static final Map<String, Double> TYPE_WEIGHTS = Map.of(
            CompanyRelationship.TYPE_TECHNOLOGY, 0.35,
            CompanyRelationship.TYPE_SKILL, 0.25,
            CompanyRelationship.TYPE_INDUSTRY, 0.20,
            CompanyRelationship.TYPE_ROLE, 0.10,
            CompanyRelationship.TYPE_LOCATION, 0.10);

    private final CompanyKnowledgeRepository companies;
    private final CompanyRelationshipRepository relationships;
    private final boolean enabled;

    public CompanySimilarityService(CompanyKnowledgeRepository companies,
                                    CompanyRelationshipRepository relationships,
                                    @Value("${company.similarity.enabled:false}") boolean enabled) {
        this.companies = companies;
        this.relationships = relationships;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public record SimilarCompany(UUID companyKnowledgeId, String companyName, int similarity, String sharedSignals) {}

    public List<SimilarCompany> similarTo(UUID userId, UUID companyKnowledgeId, int limit) {
        if (!enabled) return List.of();
        Map<String, Set<String>> base = edgesByType(companyKnowledgeId);
        if (base.isEmpty()) return List.of();

        // One pass over all of this user's edges, grouped per company — no N+1.
        Map<UUID, Map<String, Set<String>>> byCompany = relationships.findByUserId(userId).stream()
                .filter(e -> !companyKnowledgeId.equals(e.getCompanyKnowledgeId()))
                .collect(Collectors.groupingBy(CompanyRelationship::getCompanyKnowledgeId,
                        Collectors.groupingBy(CompanyRelationship::getRelationType,
                                Collectors.mapping(CompanyRelationship::getTarget, Collectors.toSet()))));

        Map<UUID, String> names = companies.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .collect(Collectors.toMap(CompanyKnowledge::getId, CompanyKnowledge::getCompanyName, (a, b) -> a));

        return byCompany.entrySet().stream()
                .map(e -> {
                    int score = similarity(base, e.getValue());
                    return new SimilarCompany(e.getKey(), names.getOrDefault(e.getKey(), "unknown"),
                            score, shared(base, e.getValue()));
                })
                .filter(s -> s.similarity() > 0)
                .sorted(Comparator.comparingInt(SimilarCompany::similarity).reversed())
                .limit(limit)
                .toList();
    }

    /** Weighted Jaccard across edge types, 0-100. Pure and unit-testable. */
    public static int similarity(Map<String, Set<String>> a, Map<String, Set<String>> b) {
        double total = 0;
        double weightUsed = 0;
        for (Map.Entry<String, Double> w : TYPE_WEIGHTS.entrySet()) {
            Set<String> sa = a.getOrDefault(w.getKey(), Set.of());
            Set<String> sb = b.getOrDefault(w.getKey(), Set.of());
            if (sa.isEmpty() && sb.isEmpty()) continue;
            weightUsed += w.getValue();
            total += w.getValue() * jaccard(sa, sb);
        }
        return weightUsed == 0 ? 0 : (int) Math.round(total / weightUsed * 100);
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private static String shared(Map<String, Set<String>> a, Map<String, Set<String>> b) {
        List<String> shared = new ArrayList<>();
        for (String type : TYPE_WEIGHTS.keySet()) {
            Set<String> intersection = new HashSet<>(a.getOrDefault(type, Set.of()));
            intersection.retainAll(b.getOrDefault(type, Set.of()));
            intersection.stream().limit(3).forEach(shared::add);
        }
        return String.join(", ", shared.subList(0, Math.min(8, shared.size())));
    }

    private Map<String, Set<String>> edgesByType(UUID companyKnowledgeId) {
        return relationships.findByCompanyKnowledgeId(companyKnowledgeId).stream()
                .collect(Collectors.groupingBy(CompanyRelationship::getRelationType,
                        Collectors.mapping(CompanyRelationship::getTarget, Collectors.toSet())));
    }
}
