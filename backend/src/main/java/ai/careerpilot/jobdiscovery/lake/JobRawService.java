package ai.careerpilot.jobdiscovery.lake;

import ai.careerpilot.domain.JobRaw;
import ai.careerpilot.jobdiscovery.provider.RawJob;
import ai.careerpilot.repo.JobRawRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists the untouched provider payload into {@code job_raw} (Phase 2A Step 4 — "never discard
 * provider data"). Idempotent on {@code (provider, external_id, checksum)}: an unchanged listing
 * re-ingested on a later run is a no-op, while a changed one is appended (history preserved).
 *
 * <p>Part of the SHADOW lake pipeline — only the orchestrator (gated by {@code JOB_DISCOVERY_ENABLED})
 * calls this. The legacy {@code JobAggregationService} never touches it.
 */
@Service
public class JobRawService {

    private static final Logger log = LoggerFactory.getLogger(JobRawService.class);

    private final JobRawRepository repo;
    // Matches the project convention (WorkflowService, JobMatchingService, …): a private mapper
    // instance, not an injected bean — this app does not expose an ObjectMapper bean.
    private final ObjectMapper mapper = new ObjectMapper();

    public JobRawService(JobRawRepository repo) {
        this.repo = repo;
    }

    /**
     * Capture one provider listing. Returns the persisted (or pre-existing) raw row, or
     * {@link Optional#empty()} when the listing carries no external id.
     */
    public Optional<JobRaw> capture(String provider, RawJob raw, UUID runId) {
        if (raw.externalId() == null || raw.externalId().isBlank()) return Optional.empty();

        String payloadJson = serialize(raw);
        String checksum = sha256(provider + "|" + raw.externalId() + "|" + payloadJson);

        // Idempotent: identical payload already captured for this listing → reuse it.
        Optional<JobRaw> existing =
                repo.existsByProviderAndExternalIdAndChecksum(provider, raw.externalId(), checksum)
                        ? repo.findFirstByProviderAndExternalIdOrderByFetchedAtDesc(provider, raw.externalId())
                        : Optional.empty();
        if (existing.isPresent()) return existing;

        JobRaw row = JobRaw.builder()
                .provider(provider)
                .externalId(raw.externalId())
                .payloadJson(payloadJson)
                .checksum(checksum)
                .runId(runId)
                .build();
        return Optional.of(repo.save(row));
    }

    /**
     * Serialize the provider's untouched source map. When a provider didn't capture one (legacy
     * 14-arg path), fall back to a faithful reconstruction from the mapped {@link RawJob} fields so
     * we never store an empty payload.
     */
    private String serialize(RawJob raw) {
        Object payload = raw.rawPayload() != null ? raw.rawPayload() : reconstruct(raw);
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Last-resort: provider map held a non-serializable value. Preserve identity at least.
            log.warn("job_raw: payload serialization failed for {}, storing minimal record: {}",
                    raw.externalId(), e.toString());
            try {
                return mapper.writeValueAsString(reconstruct(raw));
            } catch (JsonProcessingException ignored) {
                return "{\"externalId\":\"" + raw.externalId() + "\"}";
            }
        }
    }

    private static Map<String, Object> reconstruct(RawJob r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("externalId", r.externalId());
        m.put("title", r.title());
        m.put("company", r.company());
        m.put("location", r.location());
        m.put("country", r.country());
        m.put("city", r.city());
        m.put("remote", r.remote());
        m.put("salaryMin", r.salaryMin());
        m.put("salaryMax", r.salaryMax());
        m.put("currency", r.currency());
        m.put("description", r.description());
        m.put("skills", r.skills());
        m.put("sourceUrl", r.sourceUrl());
        m.put("postedDate", r.postedDate() == null ? null : r.postedDate().toString());
        return m;
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 is always available on a standard JRE; fall back to a stable hash if not.
            return Integer.toHexString(s.hashCode());
        }
    }
}
