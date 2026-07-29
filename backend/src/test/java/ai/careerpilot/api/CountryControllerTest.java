package ai.careerpilot.api;

import ai.careerpilot.api.dto.CountryDtos.CareerFitResponse;
import ai.careerpilot.api.dto.CountryDtos.CountryProfileResponse;
import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.domain.CountryIntelligence;
import ai.careerpilot.domain.SupportedCountry;
import ai.careerpilot.jobdiscovery.international.CountryIntelligenceService;
import ai.careerpilot.jobdiscovery.international.CountryMatchingCapability;
import ai.careerpilot.jobdiscovery.international.CountryMatchingCapability.CountryFitResult;
import ai.careerpilot.jobdiscovery.international.CountryTier;
import ai.careerpilot.jobdiscovery.international.SupportedCountryService;
import ai.careerpilot.mission.MissionStatus;
import ai.careerpilot.repo.CareerMissionRepository;
import ai.careerpilot.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Country Intelligence Engine, Phase 2 — {@link CountryController}. */
class CountryControllerTest {

    private final SupportedCountryService supportedCountries = mock(SupportedCountryService.class);
    private final CountryIntelligenceService intelligence = mock(CountryIntelligenceService.class);
    private final CountryMatchingCapability matching = mock(CountryMatchingCapability.class);
    private final CareerMissionRepository missions = mock(CareerMissionRepository.class);
    private final CountryController controller = new CountryController(supportedCountries, intelligence, matching, missions);
    private final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "a@b.com", "OWNER");

    private static SupportedCountry country(String code, String name) {
        return SupportedCountry.builder().countryCode(code).displayName(name).tier(CountryTier.TIER_1).active(true).build();
    }

    @Test
    void listReturnsAllActiveCountriesMergedWithIntelligence() {
        when(supportedCountries.listActive()).thenReturn(List.of(country("de", "Germany")));
        when(intelligence.forCountry("de")).thenReturn(Optional.of(
                CountryIntelligence.builder().countryCode("de").visaProbabilityScore(75).relocationDifficultyScore(55)
                        .languageRequirementScore(60).costOfLivingIndex(62).expectedSavingsScore(65)
                        .jobStabilityScore(80).techMarketScore(85).principalEngineerGrowthScore(75).aiMarketScore(78)
                        .build()));

        List<CountryProfileResponse> result = controller.list(user);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).countryCode()).isEqualTo("de");
        assertThat(result.get(0).visaProbabilityScore()).isEqualTo(75);
    }

    @Test
    void getThrowsNotFoundForAnUnsupportedCountryCode() {
        when(supportedCountries.listActive()).thenReturn(List.of(country("de", "Germany")));

        assertThatThrownBy(() -> controller.get(user, "xx")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void careerFitDefaultsToTheUsersMostRecentActiveMissionWhenNoMissionIdGiven() {
        when(supportedCountries.listActive()).thenReturn(List.of(country("de", "Germany")));
        CareerMission activeMission = CareerMission.builder().id(UUID.randomUUID()).userId(user.userId())
                .missionStatement("stmt").targetRole("role").status(MissionStatus.ACTIVE).build();
        when(missions.findFirstByUserIdAndStatusOrderByCreatedAtDesc(user.userId(), MissionStatus.ACTIVE))
                .thenReturn(Optional.of(activeMission));
        when(matching.fitForCountry(activeMission, "de"))
                .thenReturn(Optional.of(new CountryFitResult("de", "Germany", 88, "explanation")));

        CareerFitResponse response = controller.careerFit(user, "de", null);

        assertThat(response.fitScore()).isEqualTo(88);
        verify(missions).findFirstByUserIdAndStatusOrderByCreatedAtDesc(user.userId(), MissionStatus.ACTIVE);
    }

    @Test
    void careerFitUsesExplicitMissionIdWhenGiven() {
        when(supportedCountries.listActive()).thenReturn(List.of(country("de", "Germany")));
        UUID missionId = UUID.randomUUID();
        CareerMission mission = CareerMission.builder().id(missionId).userId(user.userId())
                .missionStatement("stmt").targetRole("role").build();
        when(missions.findByIdAndUserId(missionId, user.userId())).thenReturn(Optional.of(mission));
        when(matching.fitForCountry(mission, "de"))
                .thenReturn(Optional.of(new CountryFitResult("de", "Germany", 70, "explanation")));

        CareerFitResponse response = controller.careerFit(user, "de", missionId);

        assertThat(response.fitScore()).isEqualTo(70);
        verify(missions, never()).findFirstByUserIdAndStatusOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void careerFitThrowsNotFoundWhenUserHasNoActiveMission() {
        when(supportedCountries.listActive()).thenReturn(List.of(country("de", "Germany")));
        when(missions.findFirstByUserIdAndStatusOrderByCreatedAtDesc(user.userId(), MissionStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.careerFit(user, "de", null))
                .isInstanceOf(NoSuchElementException.class);
    }
}
