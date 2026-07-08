package ai.careerpilot.packageintel.api;

import ai.careerpilot.domain.ApplicationPackage;
import ai.careerpilot.domain.ApplicationPackageValidation;
import ai.careerpilot.packageintel.ApplicationPackageIntelligenceService;
import ai.careerpilot.repo.ApplicationPackageRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Phase 7.11 — read-only snapshot of a user's recent application packages + their latest validation
 * verdicts, so the Copilot's "Explain Package" skill grounds its answers in real rows instead of
 * hallucinating. Returns an empty list when the layer is dark (no packages exist yet).
 */
@Service
public class PackageExplainContextService {

    private static final int RECENT = 8;

    private final ApplicationPackageRepository packages;
    private final ApplicationPackageIntelligenceService intelligence;

    public PackageExplainContextService(ApplicationPackageRepository packages,
                                        ApplicationPackageIntelligenceService intelligence) {
        this.packages = packages;
        this.intelligence = intelligence;
    }

    public PackageExplainContext get(UUID userId) {
        List<PackageSnapshot> recent = packages.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .limit(RECENT)
                .map(p -> new PackageSnapshot(p, intelligence.latestValidation(p.getId()).orElse(null)))
                .toList();
        return new PackageExplainContext(recent);
    }

    public record PackageSnapshot(ApplicationPackage pkg, ApplicationPackageValidation validation) {}

    public record PackageExplainContext(List<PackageSnapshot> packages) {}
}
