package ai.careerpilot.companyintel;

import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.domain.CompanyRelationship;
import ai.careerpilot.domain.Job;
import ai.careerpilot.repo.CompanyRelationshipRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Deterministic edge derivation from job rows; upserts strengthen weight, dark = no writes. */
class KnowledgeGraphBuilderTest {

    @Test
    void derivesTypedEdgesFromJob() {
        Job job = Job.builder().title("Backend Engineer").company("Acme").country("DE")
                .jobFamily("TECH").skills("Java, Kafka; SQL").description("d").build();
        Map<String, Set<String>> edges = KnowledgeGraphBuilder.deriveEdges(job, null);
        assertEquals(Set.of("tech"), edges.get(CompanyRelationship.TYPE_INDUSTRY));
        assertEquals(Set.of("backend engineer"), edges.get(CompanyRelationship.TYPE_ROLE));
        assertEquals(Set.of("de"), edges.get(CompanyRelationship.TYPE_LOCATION));
        assertEquals(Set.of("java", "kafka", "sql"), edges.get(CompanyRelationship.TYPE_SKILL));
    }

    @Test
    void nullJobAndBlankFieldsProduceNoEdges() {
        assertTrue(KnowledgeGraphBuilder.deriveEdges(null, null).isEmpty());
        Job bare = Job.builder().title("x").company("Acme").description("d").build();
        Map<String, Set<String>> edges = KnowledgeGraphBuilder.deriveEdges(bare, null);
        assertFalse(edges.containsKey(CompanyRelationship.TYPE_SKILL));
        assertFalse(edges.containsKey(CompanyRelationship.TYPE_INDUSTRY));
    }

    @Test
    void darkFlagWritesNothing() {
        CompanyRelationshipRepository repo = mock(CompanyRelationshipRepository.class);
        KnowledgeGraphBuilder builder = new KnowledgeGraphBuilder(repo, false);
        Job job = Job.builder().title("t").company("Acme").skills("java").description("d").build();
        CompanyKnowledge company = CompanyKnowledge.builder().id(UUID.randomUUID()).userId(UUID.randomUUID()).build();
        assertEquals(0, builder.ingest(company, job, null, KnowledgeSource.JOB_DISCOVERY));
        verifyNoInteractions(repo);
    }

    @Test
    void reobservedEdgeGainsWeightInsteadOfDuplicating() {
        CompanyRelationshipRepository repo = mock(CompanyRelationshipRepository.class);
        CompanyKnowledge company = CompanyKnowledge.builder().id(UUID.randomUUID()).userId(UUID.randomUUID()).build();
        CompanyRelationship existing = CompanyRelationship.builder()
                .companyKnowledgeId(company.getId()).userId(company.getUserId())
                .relationType(CompanyRelationship.TYPE_SKILL).target("java").weight(2).build();
        when(repo.findByCompanyKnowledgeIdAndRelationTypeAndTarget(company.getId(),
                CompanyRelationship.TYPE_SKILL, "java")).thenReturn(Optional.of(existing));

        new KnowledgeGraphBuilder(repo, true)
                .upsert(company, CompanyRelationship.TYPE_SKILL, "Java", KnowledgeSource.LEARNING_ENGINE);

        assertEquals(3, existing.getWeight());
        assertEquals("LEARNING_ENGINE", existing.getEvidence());
        verify(repo).save(existing);
        verify(repo, never()).save(argThat(r -> r != existing && r.getTarget().equals("java")));
    }

    @Test
    void blankTargetIsIgnored() {
        CompanyRelationshipRepository repo = mock(CompanyRelationshipRepository.class);
        CompanyKnowledge company = CompanyKnowledge.builder().id(UUID.randomUUID()).userId(UUID.randomUUID()).build();
        new KnowledgeGraphBuilder(repo, true).upsert(company, CompanyRelationship.TYPE_SKILL, "  ", null);
        verify(repo, never()).save(any());
    }
}
