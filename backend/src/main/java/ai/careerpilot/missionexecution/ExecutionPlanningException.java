package ai.careerpilot.missionexecution;

/** Pre-Phase-9 Hardening — thrown when a produced {@link MissionExecutionPlan} fails {@link ExecutionValidator}. */
public class ExecutionPlanningException extends RuntimeException {

    public ExecutionPlanningException(String message) {
        super(message);
    }
}
