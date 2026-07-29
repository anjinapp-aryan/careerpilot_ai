package ai.careerpilot.strategy;

import ai.careerpilot.domain.CareerGoal;
import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.domain.StrategyPlan;
import ai.careerpilot.jobdiscovery.international.CountryMatchingCapability;
import ai.careerpilot.jobdiscovery.international.CountryMatchingCapability.CountryFitResult;
import ai.careerpilot.mission.MissionNotFoundException;
import ai.careerpilot.mission.MissionStatus;
import ai.careerpilot.repo.CareerGoalRepository;
import ai.careerpilot.repo.CareerMissionRepository;
import ai.careerpilot.repo.StrategyPlanRepository;
import ai.careerpilot.strategy.StrategyEvaluationService.GeneratedStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Strategy Engine, Phase 3 — {@link StrategyEvaluationService}. Pins the rule-based action
 * generation (one action per skill-to-acquire, one apply-for-role action, a system-design action
 * only for senior-track missions), the timeframe derivation, and that {@code generate} always
 * inserts a new plan (the append-only "plan table is its own history" design).
 */
class StrategyEvaluationServiceTest {

    private final CareerMissionRepository missions = mock(CareerMissionRepository.class);
    private final StrategyPlanRepository plans = mock(StrategyPlanRepository.class);
    private final CareerGoalRepository goals = mock(CareerGoalRepository.class);
    private final CountryMatchingCapability countryMatching = mock(CountryMatchingCapability.class);
    private final StrategyEvaluationService service = new StrategyEvaluationService(missions, plans, goals, countryMatching);

    private final UUID userId = UUID.randomUUID();
    private final UUID missionId = UUID.randomUUID();

    private CareerMission mission(String targetLevel, Integer timelineMonths, List<String> skillsToAcquire) {
        return CareerMission.builder()
                .id(missionId).userId(userId)
                .missionStatement("stmt").targetRole("Principal Java AI Architect")
                .targetLevel(targetLevel).timelineMonths(timelineMonths)
                .skillsToAcquireJson(toJson(skillsToAcquire))
                .build();
    }

    private static String toJson(List<String> xs) {
        return xs == null || xs.isEmpty() ? null : "[" + String.join(",", xs.stream().map(s -> "\"" + s + "\"").toList()) + "]";
    }

    @Test
    void generateCreatesOneActionPerSkillPlusApplyActionForSeniorTarget() {
        when(missions.findByIdAndUserId(missionId, userId))
                .thenReturn(Optional.of(mission("Principal Engineer", 24, List.of("Kubernetes", "System Design"))));
        when(plans.save(any(StrategyPlan.class))).thenAnswer(inv -> {
            StrategyPlan p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        when(goals.save(any(CareerGoal.class))).thenAnswer(inv -> inv.getArgument(0));

        GeneratedStrategy result = service.generate(userId, missionId);

        // 2 skill actions + 1 apply action + 1 system-design action (senior target) = 4
        assertThat(result.actions()).hasSize(4);
        assertThat(result.actions()).anyMatch(a -> a.getTitle().equals("Improve Kubernetes knowledge"));
        assertThat(result.actions()).anyMatch(a -> a.getTitle().contains("Apply for Principal Java AI Architect"));
        assertThat(result.actions()).anyMatch(a -> a.getTitle().equals("Practice system design"));
        assertThat(result.actions()).allMatch(a -> a.getStrategyPlanId().equals(result.plan().getId()));
        assertThat(result.actions()).allMatch(a -> a.getStatus() == MissionStatus.ACTIVE);
    }

    @Test
    void nonSeniorTargetSkipsTheSystemDesignAction() {
        when(missions.findByIdAndUserId(missionId, userId))
                .thenReturn(Optional.of(mission("Software Engineer", 12, List.of("React"))));
        when(plans.save(any(StrategyPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(goals.save(any(CareerGoal.class))).thenAnswer(inv -> inv.getArgument(0));

        GeneratedStrategy result = service.generate(userId, missionId);

        assertThat(result.actions()).noneMatch(a -> a.getTitle().equals("Practice system design"));
    }

    @Test
    void timeframeDaysDerivesFromTimelineMonthsCappedAt24Months() {
        when(missions.findByIdAndUserId(missionId, userId))
                .thenReturn(Optional.of(mission(null, 24, List.of())));
        when(plans.save(any(StrategyPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(goals.save(any(CareerGoal.class))).thenAnswer(inv -> inv.getArgument(0));

        GeneratedStrategy result = service.generate(userId, missionId);

        assertThat(result.plan().getTimeframeDays()).isEqualTo(24 * 30);
    }

    @Test
    void timeframeDaysDefaultsTo90WhenTimelineNotSet() {
        when(missions.findByIdAndUserId(missionId, userId))
                .thenReturn(Optional.of(mission(null, null, List.of())));
        when(plans.save(any(StrategyPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(goals.save(any(CareerGoal.class))).thenAnswer(inv -> inv.getArgument(0));

        GeneratedStrategy result = service.generate(userId, missionId);

        assertThat(result.plan().getTimeframeDays()).isEqualTo(90);
    }

    @Test
    void generateThrowsMissionNotFoundForWrongOwner() {
        when(missions.findByIdAndUserId(missionId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(userId, missionId))
                .isInstanceOf(MissionNotFoundException.class);
    }

    @Test
    void latestReturnsTheMostRecentPlanWithItsActions() {
        CareerMission m = mission(null, null, List.of());
        StrategyPlan plan = StrategyPlan.builder().id(UUID.randomUUID()).missionId(missionId).timeframeDays(90).build();
        when(missions.findByIdAndUserId(missionId, userId)).thenReturn(Optional.of(m));
        when(plans.findFirstByMissionIdOrderByGeneratedAtDesc(missionId)).thenReturn(Optional.of(plan));
        when(goals.findByStrategyPlanIdOrderByCreatedAtAsc(plan.getId()))
                .thenReturn(List.of(CareerGoal.builder().id(UUID.randomUUID()).title("Improve Kubernetes knowledge").build()));

        GeneratedStrategy result = service.latest(userId, missionId);

        assertThat(result.plan().getId()).isEqualTo(plan.getId());
        assertThat(result.actions()).hasSize(1);
    }

    @Test
    void completeActionMarksTheGoalCompletedAfterOwnershipCheck() {
        UUID actionId = UUID.randomUUID();
        CareerGoal action = CareerGoal.builder().id(actionId).missionId(missionId)
                .title("Improve Kubernetes knowledge").status(MissionStatus.ACTIVE).build();
        when(goals.findById(actionId)).thenReturn(Optional.of(action));
        when(missions.findByIdAndUserId(missionId, userId)).thenReturn(Optional.of(mission(null, null, List.of())));
        when(goals.save(any(CareerGoal.class))).thenAnswer(inv -> inv.getArgument(0));

        CareerGoal completed = service.completeAction(userId, actionId);

        assertThat(completed.getStatus()).isEqualTo(MissionStatus.COMPLETED);
    }

    @Test
    void completeActionThrowsWhenTheActionsMissionBelongsToAnotherUser() {
        UUID actionId = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        CareerGoal action = CareerGoal.builder().id(actionId).missionId(missionId).status(MissionStatus.ACTIVE).build();
        when(goals.findById(actionId)).thenReturn(Optional.of(action));
        when(missions.findByIdAndUserId(missionId, otherUser)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeAction(otherUser, actionId))
                .isInstanceOf(java.util.NoSuchElementException.class);
        verify(goals, never()).save(any());
    }

    @Test
    void rankCountriesForMissionDelegatesToCountryMatchingCapability() {
        CareerMission m = mission(null, null, List.of());
        List<CountryFitResult> ranked = List.of(new CountryFitResult("nl", "Netherlands", 90, "e1"));
        when(countryMatching.rankForMission(m)).thenReturn(ranked);

        List<CountryFitResult> result = service.rankCountriesForMission(m);

        assertThat(result).isEqualTo(ranked);
        verify(countryMatching).rankForMission(m);
    }
}
