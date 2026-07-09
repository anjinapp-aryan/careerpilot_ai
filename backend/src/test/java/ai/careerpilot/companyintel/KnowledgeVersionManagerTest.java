package ai.careerpilot.companyintel;

import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.domain.CompanyKnowledgeVersion;
import ai.careerpilot.repo.CompanyKnowledgeVersionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Immutable versioning: snapshots append, compare diffs sections, rollback restores without deleting. */
class KnowledgeVersionManagerTest {

    private final CompanyKnowledgeVersionRepository repo = mock(CompanyKnowledgeVersionRepository.class);
    private final KnowledgeAggregator aggregator = new KnowledgeAggregator();
    private final KnowledgeVersionManager manager = new KnowledgeVersionManager(repo, aggregator);

    @Test
    void snapshotRecordsHeadStateAndChangedSections() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CompanyKnowledge head = CompanyKnowledge.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID())
                .knowledge("{\"culture\":\"x\"}").knowledgeVersion(3).build();

        CompanyKnowledgeVersion version = manager.snapshot(head, List.of("culture"), KnowledgeSource.COMPANY_RESEARCH);

        assertEquals(3, version.getVersion());
        assertEquals("culture", version.getChangedSections());
        assertEquals("COMPANY_RESEARCH", version.getSource());
        assertEquals(head.getKnowledge(), version.getKnowledge());
    }

    @Test
    void compareDiffsOnlyChangedSections() {
        UUID id = UUID.randomUUID();
        stubVersion(id, 1, Map.of("culture", "old", "profile", "same"));
        stubVersion(id, 2, Map.of("culture", "new", "profile", "same"));

        Map<String, List<String>> diff = manager.compare(id, 1, 2);

        assertEquals(1, diff.size());
        assertEquals(List.of("old", "new"), diff.get("culture"));
    }

    @Test
    void compareHandlesAddedAndMissingVersions() {
        UUID id = UUID.randomUUID();
        stubVersion(id, 2, Map.of("skillDemand", "java"));
        when(repo.findByCompanyKnowledgeIdAndVersion(id, 1)).thenReturn(Optional.empty());

        Map<String, List<String>> diff = manager.compare(id, 1, 2);
        assertEquals(java.util.Arrays.asList(null, "java"), diff.get("skillDemand"));
    }

    @Test
    void rollbackReturnsOldSnapshotWithoutDeletingAnything() {
        UUID id = UUID.randomUUID();
        stubVersion(id, 1, Map.of("culture", "original"));

        Optional<String> restored = manager.rollbackKnowledge(id, 1);

        assertTrue(restored.isPresent());
        assertEquals("original", aggregator.parse(restored.get()).get("culture"));
        verify(repo, never()).delete(any());
        verify(repo, never()).deleteAll();
    }

    private void stubVersion(UUID companyId, int version, Map<String, String> sections) {
        when(repo.findByCompanyKnowledgeIdAndVersion(companyId, version)).thenReturn(Optional.of(
                CompanyKnowledgeVersion.builder()
                        .companyKnowledgeId(companyId).userId(UUID.randomUUID())
                        .version(version).knowledge(aggregator.write(sections)).build()));
    }
}
