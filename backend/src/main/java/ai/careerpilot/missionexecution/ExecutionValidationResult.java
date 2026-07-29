package ai.careerpilot.missionexecution;

import java.util.List;

/** Pre-Phase-9 Hardening — {@link ExecutionValidator}'s output. */
public record ExecutionValidationResult(boolean valid, List<String> errors) {

    public static ExecutionValidationResult ok() {
        return new ExecutionValidationResult(true, List.of());
    }

    public static ExecutionValidationResult invalid(List<String> errors) {
        return new ExecutionValidationResult(false, errors);
    }
}
