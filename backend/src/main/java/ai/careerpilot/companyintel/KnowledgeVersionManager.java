package ai.careerpilot.companyintel;

import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.domain.CompanyKnowledgeVersion;
import ai.careerpilot.repo.CompanyKnowledgeVersionRepository;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Phase 7.13 — owns the immutable revision history of a {@link CompanyKnowledge} row. Every update
 * appends a {@link CompanyKnowledgeVersion} snapshot (never overwrites one); rollback restores an
 * older snapshot by creating a NEW head version, so the full audit trail always survives.
 */
@Component
public class KnowledgeVersionManager {

    private final CompanyKnowledgeVersionRepository versions;
    private final KnowledgeAggregator aggregator;

    public KnowledgeVersionManager(CompanyKnowledgeVersionRepository versions, KnowledgeAggregator aggregator) {
        this.versions = versions;
        this.aggregator = aggregator;
    }

    /** Snapshot the (already updated) head knowledge as the next immutable version row. */
    public CompanyKnowledgeVersion snapshot(CompanyKnowledge head, List<String> changedSections, KnowledgeSource source) {
        return versions.save(CompanyKnowledgeVersion.builder()
                .companyKnowledgeId(head.getId())
                .userId(head.getUserId())
                .version(head.getKnowledgeVersion())
                .knowledge(head.getKnowledge())
                .changedSections(changedSections == null ? "" : String.join(",", changedSections))
                .source(source == null ? null : source.name())
                .build());
    }

    public List<CompanyKnowledgeVersion> history(UUID companyKnowledgeId) {
        return versions.findByCompanyKnowledgeIdOrderByVersionDesc(companyKnowledgeId);
    }

    /** Section-level diff between two stored versions: key -> [from, to] (null = absent). */
    public Map<String, List<String>> compare(UUID companyKnowledgeId, int fromVersion, int toVersion) {
        Map<String, String> from = sectionsOf(companyKnowledgeId, fromVersion);
        Map<String, String> to = sectionsOf(companyKnowledgeId, toVersion);
        Map<String, List<String>> diff = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>(from.keySet());
        keys.addAll(to.keySet());
        for (String key : keys) {
            String a = from.get(key);
            String b = to.get(key);
            if (!Objects.equals(a, b)) diff.put(key, Arrays.asList(a, b));
        }
        return diff;
    }

    /**
     * Restore an older snapshot as the NEW head state. The caller persists the head and this
     * manager appends the restored state as the next version — history is never deleted.
     */
    public Optional<String> rollbackKnowledge(UUID companyKnowledgeId, int toVersion) {
        return versions.findByCompanyKnowledgeIdAndVersion(companyKnowledgeId, toVersion)
                .map(CompanyKnowledgeVersion::getKnowledge);
    }

    private Map<String, String> sectionsOf(UUID companyKnowledgeId, int version) {
        return versions.findByCompanyKnowledgeIdAndVersion(companyKnowledgeId, version)
                .map(v -> aggregator.parse(v.getKnowledge()))
                .orElseGet(LinkedHashMap::new);
    }
}
