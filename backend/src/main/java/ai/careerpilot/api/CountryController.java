package ai.careerpilot.api;

import ai.careerpilot.api.dto.CountryDtos.CareerFitResponse;
import ai.careerpilot.api.dto.CountryDtos.CountryProfileResponse;
import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.domain.SupportedCountry;
import ai.careerpilot.jobdiscovery.international.CountryIntelligenceService;
import ai.careerpilot.jobdiscovery.international.CountryMatchingCapability;
import ai.careerpilot.mission.MissionStatus;
import ai.careerpilot.jobdiscovery.international.SupportedCountryService;
import ai.careerpilot.repo.CareerMissionRepository;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Country Intelligence Engine, Phase 2 — {@code GET /api/countries[/{code}[/career-fit]]}. Built
 * entirely on the existing {@code supported_countries}/{@code country_intelligence} tables
 * (extended, not duplicated, by this phase — see {@code V73__country_intelligence_profile_fields.sql}).
 * Every method inherits {@code SupportedCountryService}'s existing {@code
 * career.international.tiering.enabled} gate transitively (empty/404 when off).
 */
@RestController
@RequestMapping("/api/countries")
public class CountryController {

    private final SupportedCountryService supportedCountries;
    private final CountryIntelligenceService countryIntelligence;
    private final CountryMatchingCapability matching;
    private final CareerMissionRepository missions;

    public CountryController(SupportedCountryService supportedCountries,
                              CountryIntelligenceService countryIntelligence,
                              CountryMatchingCapability matching,
                              CareerMissionRepository missions) {
        this.supportedCountries = supportedCountries;
        this.countryIntelligence = countryIntelligence;
        this.matching = matching;
        this.missions = missions;
    }

    @GetMapping
    public List<CountryProfileResponse> list(AuthenticatedUser user) {
        return supportedCountries.listActive().stream()
                .map(c -> CountryProfileResponse.from(c, countryIntelligence.forCountry(c.getCountryCode()).orElse(null)))
                .toList();
    }

    @GetMapping("/{code}")
    public CountryProfileResponse get(AuthenticatedUser user, @PathVariable String code) {
        SupportedCountry country = findActiveCountry(code);
        return CountryProfileResponse.from(country, countryIntelligence.forCountry(code).orElse(null));
    }

    /**
     * Career fit for one country against a mission — {@code missionId} defaults to the user's
     * most recent ACTIVE mission when omitted. 404 (not an error body) when the user has no
     * active mission — a career-fit read is meaningless without one, but that's a normal,
     * expected state (e.g. a brand-new user), not a server error.
     */
    @GetMapping("/{code}/career-fit")
    public CareerFitResponse careerFit(AuthenticatedUser user, @PathVariable String code,
                                        @RequestParam(required = false) UUID missionId) {
        findActiveCountry(code); // 404s early if the country code isn't a Phase-1 supported one
        CareerMission mission = resolveMission(user.userId(), missionId);
        return matching.fitForCountry(mission, code)
                .map(CareerFitResponse::from)
                .orElseThrow(() -> new NoSuchElementException("No fit data for country: " + code));
    }

    private SupportedCountry findActiveCountry(String code) {
        return supportedCountries.listActive().stream()
                .filter(c -> c.getCountryCode().equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Country not found: " + code));
    }

    private CareerMission resolveMission(UUID userId, UUID missionId) {
        if (missionId != null) {
            return missions.findByIdAndUserId(missionId, userId)
                    .orElseThrow(() -> new NoSuchElementException("Mission not found: " + missionId));
        }
        return missions.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, MissionStatus.ACTIVE)
                .orElseThrow(() -> new NoSuchElementException("No active mission found for career-fit analysis"));
    }
}
