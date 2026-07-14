package ai.careerpilot.submission.validation;

import ai.careerpilot.domain.Job;
import ai.careerpilot.repo.JobRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7.16 — deterministic pre-flight checks for a job before the submission pipeline spends
 * any LLM calls on it: does the job exist, does it have a resolvable company + apply URL. No
 * network calls (a live HTTP reachability check would make the pipeline flaky under test and in
 * CI) — "apply URL reachable" is checked structurally (non-blank, well-formed http(s) URL), which
 * is the same standard {@code ApplicationProviderRegistry.resolve} already relies on.
 */
@Service
public class JobValidationService {

    private final JobRepository jobs;

    public JobValidationService(JobRepository jobs) {
        this.jobs = jobs;
    }

    public record ValidationResult(boolean valid, List<String> reasons, Job job) {
        public static ValidationResult ok(Job job) {
            return new ValidationResult(true, List.of(), job);
        }
        public static ValidationResult fail(List<String> reasons) {
            return new ValidationResult(false, reasons, null);
        }
    }

    public ValidationResult validate(UUID jobId) {
        if (jobId == null) return ValidationResult.fail(List.of("jobId is required"));
        Optional<Job> found = jobs.findById(jobId);
        if (found.isEmpty()) {
            return ValidationResult.fail(List.of("job not found"));
        }
        Job job = found.get();
        List<String> reasons = new ArrayList<>();
        if (job.getCompany() == null || job.getCompany().isBlank()) {
            reasons.add("job has no resolvable company");
        }
        if (job.getTitle() == null || job.getTitle().isBlank()) {
            reasons.add("job has no title");
        }
        String applyUrl = applyUrl(job);
        if (applyUrl == null || !isWellFormedHttpUrl(applyUrl)) {
            reasons.add("job has no reachable apply URL");
        }
        return reasons.isEmpty() ? ValidationResult.ok(job) : ValidationResult.fail(reasons);
    }

    /** The URL the submission pipeline treats as "the apply URL" — mirrors {@code Jobs.tsx}'s own choice. */
    public static String applyUrl(Job job) {
        if (job == null) return null;
        if (job.getSourceUrl() != null && !job.getSourceUrl().isBlank()) return job.getSourceUrl();
        if (job.getExternalUrl() != null && !job.getExternalUrl().isBlank()) return job.getExternalUrl();
        return null;
    }

    private static boolean isWellFormedHttpUrl(String url) {
        String u = url.trim().toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }
}
