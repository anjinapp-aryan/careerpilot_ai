package ai.careerpilot.jobdiscovery.scope;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Picks the {@link JobScopeStrategy} for a requested scope id. Strategies self-register via Spring,
 * so adding a new scope (e.g. "regional") is a new {@code JobScopeStrategy} bean and nothing else —
 * no switch to edit here. An unknown scope falls back to International (the safe, non-leaking default
 * — it only ever shows the candidate's explicitly preferred countries).
 */
@Component
public class JobScopeStrategyResolver {

    private final Map<String, JobScopeStrategy> byScope;
    private final JobScopeStrategy fallback;

    public JobScopeStrategyResolver(List<JobScopeStrategy> strategies) {
        this.byScope = strategies.stream()
                .collect(Collectors.toMap(s -> s.scope().toLowerCase(), Function.identity()));
        this.fallback = byScope.getOrDefault(InternationalScopeStrategy.SCOPE, strategies.get(0));
    }

    public JobScopeStrategy forScope(String scope) {
        if (scope == null) return fallback;
        return byScope.getOrDefault(scope.trim().toLowerCase(), fallback);
    }
}
