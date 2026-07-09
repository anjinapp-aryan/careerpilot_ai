package ai.careerpilot.companyintel;

import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.domain.CompanyRelationship;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobAiEnrichment;
import ai.careerpilot.repo.CompanyRelationshipRepository;
import ai.careerpilot.service.profile.JsonLists;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Phase 7.13 — derives Company Knowledge Graph edges from artifacts the platform already holds
 * (job rows, AI enrichment, learning events) and upserts them into {@code company_relationship}.
 * Re-observing a signal strengthens the edge (weight++), it never duplicates it. Deterministic —
 * no LLM involved. Gated by {@code company.graph.enabled} (default off).
 */
@Component
public class KnowledgeGraphBuilder {

    private static final int MAX_EDGE_TARGETS_PER_TYPE = 25;

    private final CompanyRelationshipRepository relationships;
    private final boolean enabled;

    public KnowledgeGraphBuilder(CompanyRelationshipRepository relationships,
                                 @Value("${company.graph.enabled:false}") boolean enabled) {
        this.relationships = relationships;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Pure derivation of edge (type, target) pairs from a job + optional enrichment. Unit-testable. */
    public static Map<String, Set<String>> deriveEdges(Job job, JobAiEnrichment enrichment) {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        if (job == null) return edges;
        if (job.getJobFamily() != null && !job.getJobFamily().isBlank()) {
            put(edges, CompanyRelationship.TYPE_INDUSTRY, job.getJobFamily());
        }
        put(edges, CompanyRelationship.TYPE_ROLE, job.getTitle());
        put(edges, CompanyRelationship.TYPE_LOCATION, job.getCountry());
        for (String skill : splitSkills(job.getSkills())) {
            put(edges, CompanyRelationship.TYPE_SKILL, skill);
        }
        if (enrichment != null) {
            for (String tech : JsonLists.toList(enrichment.getNormalizedSkillsJson())) {
                put(edges, CompanyRelationship.TYPE_TECHNOLOGY, tech);
            }
        }
        return edges;
    }

    /** Upsert the derived edges for one company; existing edges gain weight. No-op when dark. */
    public int ingest(CompanyKnowledge company, Job job, JobAiEnrichment enrichment, KnowledgeSource source) {
        if (!enabled || company == null || company.getId() == null) return 0;
        int written = 0;
        for (Map.Entry<String, Set<String>> byType : deriveEdges(job, enrichment).entrySet()) {
            int perType = 0;
            for (String target : byType.getValue()) {
                if (perType++ >= MAX_EDGE_TARGETS_PER_TYPE) break;
                upsert(company, byType.getKey(), target, source);
                written++;
            }
        }
        return written;
    }

    public void upsert(CompanyKnowledge company, String relationType, String target, KnowledgeSource source) {
        String cleaned = clean(target);
        if (cleaned.isEmpty()) return;
        CompanyRelationship edge = relationships
                .findByCompanyKnowledgeIdAndRelationTypeAndTarget(company.getId(), relationType, cleaned)
                .orElseGet(() -> CompanyRelationship.builder()
                        .companyKnowledgeId(company.getId())
                        .userId(company.getUserId())
                        .relationType(relationType)
                        .target(cleaned)
                        .weight(0)
                        .build());
        edge.setWeight(edge.getWeight() + 1);
        edge.setEvidence(source == null ? null : source.name());
        relationships.save(edge);
    }

    private static void put(Map<String, Set<String>> edges, String type, String target) {
        String cleaned = clean(target);
        if (cleaned.isEmpty()) return;
        edges.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(cleaned);
    }

    private static List<String> splitSkills(String skills) {
        if (skills == null || skills.isBlank()) return List.of();
        return Arrays.stream(skills.split("[,;|]"))
                .map(KnowledgeGraphBuilder::clean)
                .filter(s -> !s.isEmpty() && s.length() <= 64)
                .distinct()
                .toList();
    }

    private static String clean(String s) {
        if (s == null) return "";
        String cleaned = s.strip().toLowerCase(Locale.ROOT);
        return cleaned.length() > 255 ? cleaned.substring(0, 255) : cleaned;
    }
}
