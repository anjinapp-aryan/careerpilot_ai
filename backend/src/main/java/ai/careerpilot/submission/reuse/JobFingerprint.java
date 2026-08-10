package ai.careerpilot.submission.reuse;

import ai.careerpilot.domain.Job;
import ai.careerpilot.submission.validation.JobValidationService;

import java.net.URI;
import java.util.Locale;

/**
 * A deterministic job identity that survives title/salary/description/score edits — those are
 * not evidence of a different job. Prefers, in order: (1) {@code source}+{@code externalId} —
 * the same stable pair {@code JobAggregationService} already dedupes discovered jobs on, (2) the
 * normalized apply URL (scheme+host+path, no query/fragment — a tracking parameter must not mint
 * a new fingerprint), (3) the job's own row id as a last resort, which is honest: a job with
 * neither a provider id nor any URL has no cross-request identity this platform can vouch for,
 * so its fingerprint is unique per row rather than falsely claiming stability.
 */
public final class JobFingerprint {

    private JobFingerprint() {}

    public static String of(Job job) {
        if (job == null) return null;
        if (job.getSource() != null && !job.getSource().isBlank()
                && job.getExternalId() != null && !job.getExternalId().isBlank()) {
            return "provider:" + job.getSource().trim().toLowerCase(Locale.ROOT)
                    + ":" + job.getExternalId().trim();
        }
        String applyUrl = JobValidationService.applyUrl(job);
        String normalized = normalizeUrl(applyUrl);
        if (normalized != null) return "url:" + normalized;
        return "job:" + job.getId();
    }

    private static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
            return host + path;
        } catch (Exception e) {
            return url.trim().toLowerCase(Locale.ROOT);
        }
    }
}
