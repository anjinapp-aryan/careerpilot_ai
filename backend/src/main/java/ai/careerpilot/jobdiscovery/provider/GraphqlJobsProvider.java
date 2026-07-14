package ai.careerpilot.jobdiscovery.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 5.2 — GraphQL Jobs adapter. Registered so the provider framework and diagnostics can
 * report on it, but deliberately inert: neither {@code graphql.jobs} nor {@code api.graphql.jobs}
 * resolve in DNS as of this implementation (verified by direct probe — connection fails at name
 * resolution, not at the HTTP layer), so there is no live service to integrate against. Per the
 * "do not fabricate endpoints" constraint, this ships dark ({@link #isConfigured()} always false)
 * rather than invent a GraphQL schema/client against a domain that doesn't currently exist. Flip
 * {@code career.discovery.graphql-jobs.enabled} only once a real, resolvable public endpoint is
 * confirmed for this source — same precedent as {@link WellfoundProvider}.
 */
@Component
public class GraphqlJobsProvider implements JobProvider {

    private final boolean enabled;

    public GraphqlJobsProvider(@Value("${career.discovery.graphql-jobs.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override public String name() { return "graphql-jobs"; }

    /** Always false: source domain does not resolve (see class javadoc). */
    @Override public boolean isConfigured() { return false; }

    /** Whether the dark flag is on — surfaced by diagnostics even though {@link #isConfigured()} stays false. */
    public boolean isFlagEnabled() { return enabled; }

    @Override public List<RawJob> fetch() { return List.of(); }
}
