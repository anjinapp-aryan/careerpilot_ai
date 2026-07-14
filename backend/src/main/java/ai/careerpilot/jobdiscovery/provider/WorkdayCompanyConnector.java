package ai.careerpilot.jobdiscovery.provider;

/**
 * Phase 5.3 — one configured Workday tenant. Every company owns its own Workday tenant/cluster/
 * career-site combination (there is no single global Workday API), so this is parsed entirely
 * from configuration — never hardcoded.
 *
 * <p>Config line shape (semicolon-delimited fields, comma-delimited entries — see
 * {@code career.discovery.workday.companies}):
 * {@code tenant;cluster;site;displayName;country;industry;priority}. Only {@code tenant},
 * {@code cluster}, and {@code site} are required; the rest default to {@code null}/{@code 0}.
 *
 * <p>{@code tenant}/{@code cluster}/{@code site} together resolve the tenant's public CXS
 * endpoint: {@code https://{tenant}.{cluster}.myworkdayjobs.com/wday/cxs/{tenant}/{site}/jobs}
 * (verified live against real public tenants, e.g. nvidia.com and salesforce.com, during
 * implementation).
 */
public record WorkdayCompanyConnector(
        String tenant,
        String cluster,
        String site,
        String displayName,
        String country,
        String industry,
        int priority) {

    public String companyName() {
        return displayName != null && !displayName.isBlank() ? displayName : tenant;
    }

    /** {@code POST}-able search endpoint for this tenant's career site. */
    public String jobsUrl() {
        return "https://" + tenant + "." + cluster + ".myworkdayjobs.com/wday/cxs/" + tenant + "/" + site + "/jobs";
    }

    /** Public career-site URL a candidate would browse (also serves as the connector's "career URL"). */
    public String careerUrl() {
        return "https://" + tenant + "." + cluster + ".myworkdayjobs.com/" + site;
    }

    /** {@code GET}-able per-job detail endpoint (Phase 5.3.1 bounded enrichment) — {@code externalPath} includes its own leading slash. */
    public String tenantDetailUrl(String externalPath) {
        return "https://" + tenant + "." + cluster + ".myworkdayjobs.com/wday/cxs/" + tenant + "/" + site + externalPath;
    }

    /** Parse one semicolon-delimited config entry. Returns {@code null} for a blank/malformed entry. */
    static WorkdayCompanyConnector parse(String entry) {
        if (entry == null || entry.isBlank()) return null;
        String[] f = entry.split(";", -1);
        String tenant = field(f, 0);
        String cluster = field(f, 1);
        String site = field(f, 2);
        if (tenant == null || cluster == null || site == null) return null;
        String displayName = field(f, 3);
        String country = field(f, 4);
        String industry = field(f, 5);
        int priority = 0;
        String priorityStr = field(f, 6);
        if (priorityStr != null) {
            try { priority = Integer.parseInt(priorityStr); } catch (NumberFormatException ignore) { /* keep 0 */ }
        }
        return new WorkdayCompanyConnector(tenant, cluster, site, displayName, country, industry, priority);
    }

    private static String field(String[] f, int i) {
        if (i >= f.length) return null;
        String v = f[i].trim();
        return v.isEmpty() ? null : v;
    }
}
