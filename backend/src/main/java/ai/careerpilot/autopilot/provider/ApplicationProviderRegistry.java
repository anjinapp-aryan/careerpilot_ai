package ai.careerpilot.autopilot.provider;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Phase 7.4 — resolves the {@link ApplicationProvider} for an application URL. Spring injects every
 * provider bean; a specific (non-fallback) match wins, otherwise the {@code CompanyPortalProvider}
 * fallback claims any URL. Returns empty when there is no URL to match — the auto-apply engine
 * treats that as {@code HUMAN_REVIEW}.
 */
@Component
public class ApplicationProviderRegistry {

    private final List<ApplicationProvider> providers;

    public ApplicationProviderRegistry(List<ApplicationProvider> providers) {
        this.providers = providers;
    }

    public Optional<ApplicationProvider> resolve(String applicationUrl) {
        if (applicationUrl == null || applicationUrl.isBlank()) return Optional.empty();
        Optional<ApplicationProvider> specific = providers.stream()
                .filter(p -> !p.isFallback() && p.supports(applicationUrl))
                .findFirst();
        if (specific.isPresent()) return specific;
        return providers.stream()
                .filter(p -> p.isFallback() && p.supports(applicationUrl))
                .findFirst();
    }

    public String providerNameFor(String applicationUrl) {
        return resolve(applicationUrl).map(ApplicationProvider::name).orElse("unknown");
    }

    /** All registered provider names, for diagnostics. */
    public List<String> providerNames() {
        return providers.stream().map(ApplicationProvider::name).sorted().toList();
    }
}
