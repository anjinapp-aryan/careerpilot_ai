package ai.careerpilot.missionexecution;

/**
 * Pre-Phase-9 Hardening — structural sanity checks on a {@link MissionExecutionPlan}: no
 * duplicate workflow types across decisions, every queue only contains decisions with the
 * matching policy, every blocked decision actually has a reason it's blocked. Never mutates the
 * plan — a failing validation causes {@link MissionExecutionEngine#plan} to throw {@link
 * ExecutionPlanningException} rather than return a broken plan.
 */
public interface ExecutionValidator {

    ExecutionValidationResult validate(MissionExecutionPlan plan);
}
