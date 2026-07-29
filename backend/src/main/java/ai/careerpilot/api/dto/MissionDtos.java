package ai.careerpilot.api.dto;

import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.mission.MissionStatus;
import ai.careerpilot.service.profile.JsonLists;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Mission Engine, Phase 1 — request/response shapes for {@code MissionController}. */
public final class MissionDtos {

    private MissionDtos() {}

    /**
     * Create/update body. {@code status} is optional on create (defaults to {@link
     * MissionStatus#ACTIVE} in the service layer) and optional on update (omitting it leaves the
     * existing status unchanged).
     */
    public record MissionRequest(
            @NotBlank(message = "must not be blank") @Size(max = 2000) String missionStatement,
            @NotBlank(message = "must not be blank") @Size(max = 200) String targetRole,
            @Size(max = 100) String targetLevel,
            List<String> targetIndustries,
            List<String> targetCountries,
            @PositiveOrZero BigDecimal salaryExpectationMin,
            @PositiveOrZero BigDecimal salaryExpectationMax,
            @Size(max = 10) String salaryCurrency,
            @Positive Integer timelineMonths,
            @Size(max = 200) String careerDirection,
            List<String> skillsToAcquire,
            List<String> currentSkills,
            @Size(max = 4000) String careerAmbition,
            MissionStatus status
    ) {
        public CareerMission toEntity(UUID userId) {
            return CareerMission.builder()
                    .userId(userId)
                    .missionStatement(missionStatement)
                    .targetRole(targetRole)
                    .targetLevel(targetLevel)
                    .targetIndustriesJson(JsonLists.toJson(targetIndustries))
                    .targetCountriesJson(JsonLists.toJson(targetCountries))
                    .salaryExpectationMin(salaryExpectationMin)
                    .salaryExpectationMax(salaryExpectationMax)
                    .salaryCurrency(salaryCurrency)
                    .timelineMonths(timelineMonths)
                    .careerDirection(careerDirection)
                    .skillsToAcquireJson(JsonLists.toJson(skillsToAcquire))
                    .currentSkillsJson(JsonLists.toJson(currentSkills))
                    .careerAmbition(careerAmbition)
                    .status(status != null ? status : MissionStatus.ACTIVE)
                    .build();
        }

        /** Applies this request onto an existing entity in place (update path). */
        public void applyTo(CareerMission m) {
            m.setMissionStatement(missionStatement);
            m.setTargetRole(targetRole);
            m.setTargetLevel(targetLevel);
            m.setTargetIndustriesJson(JsonLists.toJson(targetIndustries));
            m.setTargetCountriesJson(JsonLists.toJson(targetCountries));
            m.setSalaryExpectationMin(salaryExpectationMin);
            m.setSalaryExpectationMax(salaryExpectationMax);
            m.setSalaryCurrency(salaryCurrency);
            m.setTimelineMonths(timelineMonths);
            m.setCareerDirection(careerDirection);
            m.setSkillsToAcquireJson(JsonLists.toJson(skillsToAcquire));
            m.setCurrentSkillsJson(JsonLists.toJson(currentSkills));
            m.setCareerAmbition(careerAmbition);
            if (status != null) m.setStatus(status);
        }
    }

    public record MissionResponse(
            UUID id, UUID userId, String missionStatement, String targetRole, String targetLevel,
            List<String> targetIndustries, List<String> targetCountries,
            BigDecimal salaryExpectationMin, BigDecimal salaryExpectationMax, String salaryCurrency,
            Integer timelineMonths, String careerDirection,
            List<String> skillsToAcquire, List<String> currentSkills, String careerAmbition,
            MissionStatus status, Instant createdAt, Instant updatedAt
    ) {
        public static MissionResponse from(CareerMission m) {
            return new MissionResponse(
                    m.getId(), m.getUserId(), m.getMissionStatement(), m.getTargetRole(), m.getTargetLevel(),
                    JsonLists.toList(m.getTargetIndustriesJson()), JsonLists.toList(m.getTargetCountriesJson()),
                    m.getSalaryExpectationMin(), m.getSalaryExpectationMax(), m.getSalaryCurrency(),
                    m.getTimelineMonths(), m.getCareerDirection(),
                    JsonLists.toList(m.getSkillsToAcquireJson()), JsonLists.toList(m.getCurrentSkillsJson()),
                    m.getCareerAmbition(), m.getStatus(), m.getCreatedAt(), m.getUpdatedAt());
        }
    }
}
