package ai.careerpilot.packageintel.api;

import ai.careerpilot.domain.ApplicationPackage;
import ai.careerpilot.domain.ApplicationPackageVersion;
import ai.careerpilot.packageintel.ApplicationPackageIntelligenceService;
import ai.careerpilot.packageintel.api.dto.ApplicationPackageDtos.*;
import ai.careerpilot.repo.ApplicationPackageRepository;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7.11 — the authenticated human surface over the application package intelligence layer. Every
 * read is scoped to the caller ({@code user.userId()}) with the app's manual multi-tenant check — an
 * id belonging to another user reads as 404, never leaks. All engines ship dark, so with stock flags
 * the reads return 404/absent and {@code POST /generate} is a no-op returning 404 (it only ever
 * ASSEMBLES a package — it never submits an application).
 */
@RestController
@RequestMapping("/api/application-package")
public class ApplicationPackageController {

    private final ApplicationPackageIntelligenceService intelligence;
    private final ApplicationPackageRepository packages;

    public ApplicationPackageController(ApplicationPackageIntelligenceService intelligence,
                                        ApplicationPackageRepository packages) {
        this.intelligence = intelligence;
        this.packages = packages;
    }

    /** The head package by id. 404 when missing or owned by another user. */
    @GetMapping("/{id}")
    public ResponseEntity<PackageResponse> get(AuthenticatedUser user, @PathVariable UUID id) {
        return owned(user, id).map(p -> ResponseEntity.ok(PackageResponse.from(p)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Immutable version history (append-only) for a package. Empty list when history is off / none exist. */
    @GetMapping("/{id}/history")
    public ResponseEntity<List<VersionResponse>> history(AuthenticatedUser user, @PathVariable UUID id) {
        if (owned(user, id).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(intelligence.history(id).stream().map(VersionResponse::from).toList());
    }

    /** Compare the current head against one historical version. 404 when either is absent. */
    @GetMapping("/{id}/compare/{version}")
    public ResponseEntity<CompareResponse> compare(AuthenticatedUser user, @PathVariable UUID id,
                                                   @PathVariable int version) {
        Optional<ApplicationPackage> head = owned(user, id);
        Optional<ApplicationPackageVersion> against = intelligence.version(id, version);
        if (head.isEmpty() || against.isEmpty()) return ResponseEntity.notFound().build();
        PackageResponse current = PackageResponse.from(head.get());
        VersionResponse ver = VersionResponse.from(against.get());
        return ResponseEntity.ok(new CompareResponse(current, ver, changedArtifacts(head.get(), against.get())));
    }

    /** Latest validation verdict + gate detail for a package. 404 when never validated. */
    @GetMapping("/{id}/validation")
    public ResponseEntity<ValidationResponse> validation(AuthenticatedUser user, @PathVariable UUID id) {
        if (owned(user, id).isEmpty()) return ResponseEntity.notFound().build();
        return intelligence.latestValidation(id).map(v -> ResponseEntity.ok(ValidationResponse.from(v)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Assemble (or re-assemble a new version of) and validate the package for a job. ONLY assembles —
     * never submits an application. 404 when disabled or when there is nothing to assemble yet.
     */
    @PostMapping("/{jobId}/generate")
    public ResponseEntity<PackageResponse> generate(AuthenticatedUser user, @PathVariable UUID jobId) {
        return intelligence.generate(user.userId(), jobId)
                .map(p -> ResponseEntity.ok(PackageResponse.from(p)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Optional<ApplicationPackage> owned(AuthenticatedUser user, UUID id) {
        return packages.findById(id).filter(p -> Objects.equals(p.getUserId(), user.userId()));
    }

    private static List<String> changedArtifacts(ApplicationPackage head, ApplicationPackageVersion old) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(head.getResumeTailoringId(), old.getResumeTailoringId())) changed.add("resumeTailoring");
        if (!Objects.equals(head.getCoverLetterId(), old.getCoverLetterId())) changed.add("coverLetter");
        if (!Objects.equals(head.getAtsAnalysisId(), old.getAtsAnalysisId())) changed.add("atsAnalysis");
        if (!Objects.equals(head.getGapAnalysisId(), old.getGapAnalysisId())) changed.add("gapAnalysis");
        if (!Objects.equals(head.getAtsExplanationId(), old.getAtsExplanationId())) changed.add("atsExplanation");
        if (!Objects.equals(head.getStatus(), old.getStatus())) changed.add("status");
        return changed;
    }
}
