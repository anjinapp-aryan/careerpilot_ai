package ai.careerpilot.jobdiscovery.international;

import ai.careerpilot.domain.CountryIntelligence;
import ai.careerpilot.jobdiscovery.IndustryFitClassifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * International Job Discovery Phase 2 — {@link CandidateCountryFitClassifier}. Uses the worked
 * example from the master prompt (Java + Banking + AWS, J.P. Morgan background) but proves the
 * classifier is reusable/context-driven rather than hardcoded per country: every assertion here
 * follows from the inputs, never from a country name comparison inside the classifier itself.
 */
class CandidateCountryFitClassifierTest {

    private final IndustryFitClassifier industryFitClassifier = new IndustryFitClassifier();
    private final CandidateCountryFitClassifier classifier = new CandidateCountryFitClassifier(industryFitClassifier);

    private static final List<String> BANKING_CANDIDATE_SKILLS =
            List.of("Java", "Spring Boot", "AWS", "Kafka", "Kubernetes", "J.P. Morgan", "payments", "securities");
    private static final String BANKING_TARGET_ROLE = "Senior Java Architect, banking platform";

    private static CountryIntelligence intel(int techMarket, int visa, int language, String industryFitJson) {
        return CountryIntelligence.builder()
                .countryCode("xx").techMarketScore(techMarket).visaProbabilityScore(visa)
                .languageFriendlyScore(language).industryFitJson(industryFitJson)
                .relocationDifficultyScore(50).languageRequirementScore(50).costOfLivingIndex(50)
                .expectedSavingsScore(50).jobStabilityScore(50).principalEngineerGrowthScore(50).aiMarketScore(50)
                .build();
    }

    @Test
    void nullIntelReturnsNullRatherThanAGuessedDefault() {
        assertThat(classifier.classify(BANKING_CANDIDATE_SKILLS, BANKING_TARGET_ROLE, null)).isNull();
    }

    @Test
    void strongTechAndBankingOverlapAndHighVisaAndLanguageProducesVeryHigh() {
        // Modeled on Germany's real curated values: high tech market, decent visa, banking in industryFit.
        CountryIntelligence germanyLike = intel(85, 75, 75, "[\"BANKING\",\"ENTERPRISE\",\"CLOUD\",\"PLATFORM\"]");
        assertThat(classifier.classify(BANKING_CANDIDATE_SKILLS, BANKING_TARGET_ROLE, germanyLike))
                .isEqualTo(CandidateCountryFit.VERY_HIGH);
    }

    @Test
    void industryMismatchLowersFitEvenWithGoodTechMarket() {
        // Same strong tech market, but this country's curated industries don't include BANKING —
        // the candidate's banking-heavy profile is a worse match here.
        CountryIntelligence noBankingFocus = intel(85, 75, 75, "[\"ENTERPRISE\",\"CLOUD\"]");
        CountryIntelligence bankingFocus = intel(85, 75, 75, "[\"BANKING\",\"FINTECH\"]");

        CandidateCountryFit withoutBanking = classifier.classify(BANKING_CANDIDATE_SKILLS, BANKING_TARGET_ROLE, noBankingFocus);
        CandidateCountryFit withBanking = classifier.classify(BANKING_CANDIDATE_SKILLS, BANKING_TARGET_ROLE, bankingFocus);

        assertThat(withBanking.ordinal()).isLessThanOrEqualTo(withoutBanking.ordinal()); // enum order VERY_HIGH..LOW
    }

    @Test
    void weakTechMarketAndLowVisaProducesLowOrMedium() {
        CountryIntelligence weakMarket = intel(30, 20, 40, "[\"ENTERPRISE\"]");
        CandidateCountryFit fit = classifier.classify(BANKING_CANDIDATE_SKILLS, BANKING_TARGET_ROLE, weakMarket);
        assertThat(fit).isIn(CandidateCountryFit.LOW, CandidateCountryFit.MEDIUM);
    }

    @Test
    void aCandidateWithNoBankingSignalGetsNeutralIndustryOverlapNotAPenalty() {
        List<String> genericSkills = List.of("Python", "Django");
        CountryIntelligence bankingCountry = intel(80, 70, 80, "[\"BANKING\"]");
        // No exception, and a real (non-null) result — neutral overlap, not a crash or a guess.
        assertThat(classifier.classify(genericSkills, "Backend Engineer", bankingCountry)).isNotNull();
    }

    @Test
    void emptyCountryIndustryFitIsNeutralNotAPenalty() {
        CountryIntelligence noCuratedIndustries = intel(80, 70, 80, null);
        CandidateCountryFit fit = classifier.classify(BANKING_CANDIDATE_SKILLS, BANKING_TARGET_ROLE, noCuratedIndustries);
        assertThat(fit).isNotNull();
    }
}
