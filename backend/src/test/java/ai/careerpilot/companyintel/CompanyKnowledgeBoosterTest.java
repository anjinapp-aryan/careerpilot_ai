package ai.careerpilot.companyintel;

import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.repo.CompanyKnowledgeRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Recommendation integration — dark = 0 for every job (ranking byte-for-byte unchanged). */
class CompanyKnowledgeBoosterTest {

    @Test
    void darkFlagsReturnZeroAndNeverTouchTheRepo() {
        CompanyKnowledgeRepository repo = mock(CompanyKnowledgeRepository.class);
        assertEquals(0, new CompanyKnowledgeBooster(repo, false, false).computeBoost(UUID.randomUUID(), "Acme"));
        assertEquals(0, new CompanyKnowledgeBooster(repo, true, false).computeBoost(UUID.randomUUID(), "Acme"));
        assertEquals(0, new CompanyKnowledgeBooster(repo, false, true).computeBoost(UUID.randomUUID(), "Acme"));
        verifyNoInteractions(repo);
    }

    @Test
    void unknownCompanyBoostsZero() {
        CompanyKnowledgeRepository repo = mock(CompanyKnowledgeRepository.class);
        when(repo.findByUserIdAndNormalizedName(any(), anyString())).thenReturn(Optional.empty());
        assertEquals(0, new CompanyKnowledgeBooster(repo, true, true).computeBoost(UUID.randomUUID(), "Acme"));
    }

    @Test
    void boostIsClampedToPlusMinusFive() {
        assertEquals(5, CompanyKnowledgeBooster.boostFor(scored(100, 100, 100)));
        assertEquals(-5, CompanyKnowledgeBooster.boostFor(scored(0, 0, 0)));
        assertEquals(0, CompanyKnowledgeBooster.boostFor(scored(50, 50, 50)));
    }

    @Test
    void missingScoresContributeNothing() {
        assertEquals(0, CompanyKnowledgeBooster.boostFor(CompanyKnowledge.builder().build()));
    }

    @Test
    void repoFailureIsSwallowedAsZero() {
        CompanyKnowledgeRepository repo = mock(CompanyKnowledgeRepository.class);
        when(repo.findByUserIdAndNormalizedName(any(), anyString())).thenThrow(new RuntimeException("boom"));
        assertEquals(0, new CompanyKnowledgeBooster(repo, true, true).computeBoost(UUID.randomUUID(), "Acme"));
    }

    private static CompanyKnowledge scored(int quality, int hiring, int tech) {
        return CompanyKnowledge.builder()
                .qualityScore(quality).hiringProbability(hiring).technologyMatch(tech).build();
    }
}
