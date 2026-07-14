package ai.careerpilot.jobdiscovery.provider;

/**
 * Phase 5.3 — one configured company connector for an inert Enterprise ATS adapter (Taleo,
 * SuccessFactors). Shared by both so their config-line format and diagnostics shape stay
 * identical; kept separate from {@link WorkdayCompanyConnector} because Workday's connector is
 * live and needs the tenant/cluster/site fields a working CXS call requires, while these two are
 * "connector architecture only" (see {@link TaleoProvider}/{@link SuccessFactorsProvider}) and
 * only need the generic company-identity fields the spec asks every connector to carry.
 *
 * <p>Config line shape (semicolon-delimited fields, comma-delimited entries):
 * {@code company;careerUrl;country;industry;priority}. Only {@code company} is required.
 */
public record EnterpriseAtsCompanyConnector(
        String company,
        String careerUrl,
        String country,
        String industry,
        int priority) {

    static EnterpriseAtsCompanyConnector parse(String entry) {
        if (entry == null || entry.isBlank()) return null;
        String[] f = entry.split(";", -1);
        String company = field(f, 0);
        if (company == null) return null;
        String careerUrl = field(f, 1);
        String country = field(f, 2);
        String industry = field(f, 3);
        int priority = 0;
        String priorityStr = field(f, 4);
        if (priorityStr != null) {
            try { priority = Integer.parseInt(priorityStr); } catch (NumberFormatException ignore) { /* keep 0 */ }
        }
        return new EnterpriseAtsCompanyConnector(company, careerUrl, country, industry, priority);
    }

    static java.util.List<EnterpriseAtsCompanyConnector> parseAll(String csv) {
        if (csv == null || csv.isBlank()) return java.util.List.of();
        java.util.List<EnterpriseAtsCompanyConnector> out = new java.util.ArrayList<>();
        for (String entry : csv.split(",")) {
            EnterpriseAtsCompanyConnector c = parse(entry);
            if (c != null) out.add(c);
        }
        return out;
    }

    private static String field(String[] f, int i) {
        if (i >= f.length) return null;
        String v = f[i].trim();
        return v.isEmpty() ? null : v;
    }
}
