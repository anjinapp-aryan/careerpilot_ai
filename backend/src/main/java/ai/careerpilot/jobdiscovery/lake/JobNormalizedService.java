package ai.careerpilot.jobdiscovery.lake;

import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobNormalized;
import ai.careerpilot.jobdiscovery.JobNormalizer;
import ai.careerpilot.jobdiscovery.provider.RawJob;
import ai.careerpilot.repo.JobNormalizedRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Collapses a provider {@link RawJob} into the single canonical {@link JobNormalized} schema (Phase
 * 2A Step 5). Reuses the legacy {@link JobNormalizer#toJob} so country/city inference, HTML
 * stripping, skill extraction, and keyword enrichment behave identically to the live pipeline — then
 * maps the resulting transient {@link Job} onto the lake's own table (never persisting into
 * {@code jobs}).
 *
 * <p>Idempotent on {@code (provider, external_job_id)}: re-ingest updates the row in place and
 * refreshes its checksum so the lake can cheaply detect content changes.
 */
@Service
public class JobNormalizedService {

    private final JobNormalizer normalizer;
    private final JobNormalizedRepository repo;

    public JobNormalizedService(JobNormalizer normalizer, JobNormalizedRepository repo) {
        this.normalizer = normalizer;
        this.repo = repo;
    }

    /** Normalize and upsert one listing. {@code rawId} links back to the {@code job_raw} source row. */
    public JobNormalized upsert(RawJob raw, String provider, UUID rawId) {
        // Reuse the exact legacy inference/enrichment to build a transient Job (never saved).
        Job j = normalizer.toJob(raw, provider);
        String checksum = JobRawService.sha256(
                safe(j.getTitle()) + "|" + safe(j.getCompany()) + "|" + safe(j.getCountry())
                        + "|" + safe(j.getCity()) + "|" + safe(j.getDescription()));

        Optional<JobNormalized> existing = repo.findByProviderAndExternalJobId(provider, raw.externalId());
        JobNormalized row = existing.orElseGet(JobNormalized::new);

        row.setRawId(rawId);
        row.setProvider(provider);
        row.setExternalJobId(raw.externalId());
        row.setTitle(j.getTitle());
        row.setCompany(j.getCompany());
        row.setCountry(j.getCountry());
        row.setCity(j.getCity());
        row.setLocationText(j.getLocation());
        row.setDescription(j.getDescription());
        row.setApplyUrl(firstNonBlank(j.getExternalUrl(), j.getSourceUrl()));
        row.setSalaryMin(j.getSalaryMin());
        row.setSalaryMax(j.getSalaryMax());
        row.setRemote(j.getRemote());
        row.setVisaSupported(j.getSponsorshipAvailable());
        row.setPostedDate(j.getPostedDate());
        row.setSourceUrl(j.getSourceUrl());
        row.setChecksum(checksum);
        return repo.save(row);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
