package ai.careerpilot.jobdiscovery.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Phase 5.3 — Enterprise ATS Connector Framework, Workday adapter. Real, working, keyless public
 * JSON search API that every public Workday tenant exposes for its own career site — verified
 * live during implementation against nvidia.com and salesforce.com's tenants (200 OK, real job
 * data, no auth). There is no single global Workday API: each company owns its own
 * tenant/cluster/site combination, so this is a multi-tenant connector driven entirely by
 * {@link WorkdayCompanyConnector} configuration (see that class for the config-line format) —
 * never a hardcoded company list, matching the Ashby/SmartRecruiters/Greenhouse
 * one-call-per-configured-tenant shape.
 *
 * <p><b>Scope note (search endpoint):</b> the search endpoint ({@code POST .../jobs}) returns
 * only list-level fields (title, path, a relative "N Locations" summary — <i>not</i> a real place
 * name — and a requisition id in {@code bulletFields[0]}) — full descriptions/skills/real location
 * require one additional per-job {@code GET} call, which is <b>not</b> made by default (a tenant
 * can have 1000+ open roles; N+1 detail calls would violate the "no sequential bottlenecks"
 * constraint and this pipeline's existing one/few-call-per-source shape).
 *
 * <p><b>Phase 5.3.1 — optional bounded detail enrichment:</b> when
 * {@code career.discovery.workday.detail-fetch-limit} is set {@code > 0} (default {@code 0},
 * i.e. off — zero behavior change from Phase 5.3), the first N jobs per company per sync get one
 * additional {@code GET .../wday/cxs/{tenant}/{site}{externalPath}} call, verified live against
 * real NVIDIA and Salesforce tenant responses during implementation to extract only genuinely
 * present, structural fields: real {@code location} (replacing the list endpoint's
 * count-summary), {@code additionalLocations}, ISO {@code startDate} (→ {@code postedDate}),
 * {@code timeType} (employment type), {@code remoteType} (refines the remote/hybrid/onsite
 * guess), {@code externalUrl} (authoritative apply URL), {@code jobReqId} (requisition id), and
 * {@code hiringOrganization.name} (the closest real field to "business unit"/"hiring team" —
 * Workday's public API does not expose a distinct department or a list of hiring-team members).
 * <b>Not extracted, because they are not structural fields in either verified tenant's response:</b>
 * salary/currency (some tenants embed compensation as free text inside the HTML description —
 * deliberately not scraped/parsed here, since that would be exactly the kind of fragile,
 * unverifiable inference this phase forbids), experience level, closing date, and a structured
 * hiring-team roster. Skills/tech stack are intentionally left for the existing, separate
 * {@link ai.careerpilot.jobdiscovery.enrich.JobAiEnrichmentExtractor} LLM enrichment stage
 * (already reused — it processes every newly-discovered job regardless of source) rather than
 * re-derived here. All detail-only fields are merged into the captured {@code rawPayload} so
 * nothing is lost even where {@link ai.careerpilot.jobdiscovery.provider.RawJob}'s fixed shape
 * has no matching column.
 */
@Component
public class WorkdayProvider extends AbstractWebJobProvider {

    private static final Logger log = LoggerFactory.getLogger(WorkdayProvider.class);

    private final boolean enabled;
    private final boolean enterpriseEnabled;
    private final List<WorkdayCompanyConnector> companies;
    private final int limit;
    private final int throttleMs;
    private final int detailFetchLimit;

    public WorkdayProvider(
            @Value("${career.discovery.workday.enabled:false}") boolean enabled,
            @Value("${career.discovery.enterprise.enabled:false}") boolean enterpriseEnabled,
            @Value("${career.discovery.workday.companies:}") String companiesCsv,
            @Value("${career.discovery.workday.limit:50}") int limit,
            @Value("${career.discovery.workday.throttle-ms:300}") int throttleMs,
            @Value("${career.discovery.workday.detail-fetch-limit:0}") int detailFetchLimit,
            @Value("${jobs.discovery.user-agent:CareerPilotAI/1.0 (+https://careerpilot.ai)}") String userAgent) {
        // No fixed base URL: every tenant has its own host, so each call below passes an
        // absolute URI, which Spring's WebClient resolves as-is regardless of the builder's baseUrl.
        super("", userAgent);
        this.enabled = enabled;
        this.enterpriseEnabled = enterpriseEnabled;
        this.companies = parseCompanies(companiesCsv);
        this.limit = Math.max(1, limit);
        this.throttleMs = throttleMs;
        this.detailFetchLimit = Math.max(0, detailFetchLimit);
    }

    @Override public String name() { return "workday"; }

    /** {@code career.discovery.enterprise.enabled} is the framework-wide master switch (AND'd with the per-provider flag). */
    @Override public boolean isConfigured() { return enterpriseEnabled && enabled && !companies.isEmpty(); }

    /** Exposed for diagnostics: the configured tenants and their static connector metadata. */
    public List<WorkdayCompanyConnector> companies() { return Collections.unmodifiableList(companies); }

    @Override
    public List<RawJob> fetch() {
        List<RawJob> jobs = new ArrayList<>();
        for (WorkdayCompanyConnector company : companies) {
            try {
                jobs.addAll(fetchCompany(company));
            } catch (Exception e) {
                log.warn("workday: tenant {} fetch failed: {}", company.tenant(), e.toString());
            }
            throttle();
        }
        log.info("workday: fetched {} jobs across {} tenants", jobs.size(), companies.size());
        return jobs;
    }

    /**
     * Phase 5.3.1 — fetch exactly one configured tenant, for {@code CompanyConnectorService}'s
     * per-connector manual sync. Same HTTP/mapping logic as {@link #fetch()}'s per-tenant loop,
     * just without iterating the rest of the configured tenants.
     */
    public List<RawJob> fetchOne(WorkdayCompanyConnector company) {
        return fetchCompany(company);
    }

    private List<RawJob> fetchCompany(WorkdayCompanyConnector company) {
        Map<String, Object> body = client.post()
                .uri(company.jobsUrl())
                .bodyValue(Map.of("limit", limit, "offset", 0))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(20))
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)))
                .block();

        List<RawJob> jobs = new ArrayList<>();
        if (body == null || !(body.get("jobPostings") instanceof List<?> list)) return jobs;
        int detailCallsMade = 0;
        for (Object o : list) {
            try {
                Map<String, Object> jobMap = asMap(o);
                RawJob job = mapJob(jobMap, company);
                if (job == null) continue;
                if (detailFetchLimit > 0 && detailCallsMade < detailFetchLimit) {
                    detailCallsMade++;
                    try {
                        job = enrichWithDetail(job, jobMap, company);
                    } catch (Exception e) {
                        log.warn("workday: tenant {} detail fetch failed for {}: {}", company.tenant(), job.externalId(), e.toString());
                    }
                }
                jobs.add(job);
            } catch (Exception e) {
                log.warn("workday: tenant {} skipping malformed job record: {}", company.tenant(), e.toString());
            }
        }
        return jobs;
    }

    /** Package-private for direct unit testing without standing up a mock HTTP server. */
    RawJob mapJob(Map<String, Object> m, WorkdayCompanyConnector company) {
        String externalPath = str(m.get("externalPath"));
        if (externalPath == null) return null;
        // externalPath is the tenant-unique identifier (e.g. "/job/US-CA-Santa-Clara/Title_JR123");
        // stable across polls, so it doubles as the dedup external id.
        String externalId = externalPath;

        String location = str(m.get("locationsText"));
        boolean remote = location != null && location.toLowerCase().contains("remote");

        // bulletFields[0] is the requisition id on the list endpoint too — captured into the raw
        // payload (not a RawJob column) so it's never lost, even without detail enrichment.
        Map<String, Object> enrichedRaw = new java.util.LinkedHashMap<>(m);
        List<?> bulletFields = m.get("bulletFields") instanceof List<?> bf ? bf : List.of();
        if (!bulletFields.isEmpty()) enrichedRaw.put("requisitionId", str(bulletFields.get(0)));

        return new RawJob(
                externalId,
                str(m.get("title")),
                company.companyName(),
                location,
                company.country(), null,
                remote,
                null, null, null,
                null,           // description not fetched — see class javadoc (unless detail-enriched below)
                List.of(),      // skills not exposed by Workday — left to the existing enrichment stage
                company.careerUrl() + externalPath,
                null,           // postedOn is relative text, not a parseable timestamp
                enrichedRaw);   // capture raw payload for the Job Lake (ignored by legacy normalizer)
    }

    /**
     * Phase 5.3.1 — bounded opt-in enrichment: one {@code GET} for the tenant-real detail
     * payload, merging only fields verified present in real tenant responses (see class javadoc).
     */
    private RawJob enrichWithDetail(RawJob job, Map<String, Object> listMap, WorkdayCompanyConnector company) {
        String externalPath = job.externalId();
        Map<String, Object> detail = client.get()
                .uri(company.tenantDetailUrl(externalPath))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(20))
                .retryWhen(Retry.backoff(1, Duration.ofSeconds(1)))
                .block();
        if (detail == null) return job;

        Map<String, Object> info = asMap(detail.get("jobPostingInfo"));
        Map<String, Object> hiringOrg = asMap(detail.get("hiringOrganization"));
        if (info.isEmpty()) return job;

        String realLocation = firstNonBlank(str(info.get("location")), job.location());
        String remoteType = str(info.get("remoteType"));
        Boolean remote = job.remote();
        if (remoteType != null) {
            String rt = remoteType.toLowerCase();
            remote = rt.contains("remote") ? Boolean.TRUE : rt.contains("office") || rt.contains("on-site") || rt.contains("onsite") ? Boolean.FALSE : remote;
        }
        String description = stripHtml(str(info.get("jobDescription")));
        String applyUrl = firstNonBlank(str(info.get("externalUrl")), job.sourceUrl());
        java.time.Instant postedDate = isoLocalDate(str(info.get("startDate")));

        Map<String, Object> mergedRaw = new java.util.LinkedHashMap<>(job.rawPayload());
        mergedRaw.put("detail", detail);
        if (!hiringOrg.isEmpty()) mergedRaw.put("hiringOrganizationName", str(hiringOrg.get("name")));
        if (info.get("timeType") != null) mergedRaw.put("employmentType", str(info.get("timeType")));
        if (info.get("additionalLocations") != null) mergedRaw.put("additionalLocations", info.get("additionalLocations"));
        if (remoteType != null) mergedRaw.put("remoteType", remoteType);

        return new RawJob(
                job.externalId(), job.title(), job.company(),
                realLocation, job.country(), job.city(),
                remote,
                job.salaryMin(), job.salaryMax(), job.currency(),
                description != null ? description : job.description(),
                job.skills(),
                applyUrl,
                postedDate,
                mergedRaw);
    }

    private static String stripHtml(String html) {
        return html == null ? null : html.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    /** Best-effort ISO local-date parse (Workday's {@code startDate} is e.g. "2026-07-13"); tolerant of nulls/garbage. */
    private static java.time.Instant isoLocalDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(s).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private void throttle() {
        if (throttleMs <= 0) return;
        try {
            Thread.sleep(throttleMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<WorkdayCompanyConnector> parseCompanies(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<WorkdayCompanyConnector> out = new ArrayList<>();
        for (String entry : csv.split(",")) {
            WorkdayCompanyConnector c = WorkdayCompanyConnector.parse(entry);
            if (c != null) out.add(c);
        }
        return out;
    }
}
