package ai.careerpilot.jobdiscovery.discovery;

/**
 * Gap A — Company Discovery Agent. Result of a successful {@link CompanySource#probe} — one
 * company confirmed to have a live, keyless-public ATS endpoint for a given candidate slug.
 * Mirrors {@link ai.careerpilot.jobdiscovery.provider.WorkdayCompanyConnector}'s field shape
 * (tenant/cluster/site) so a hit can be mapped 1:1 onto a new {@link
 * ai.careerpilot.jobdiscovery.enterprise.CompanyConnector} row. Fields are nullable per ATS type:
 * {@code tenant}/{@code cluster}/{@code site} are Workday-only and null for Greenhouse/Ashby/
 * SmartRecruiters hits, exactly like the existing {@code CompanyConnector} entity already allows.
 */
public record DiscoveredCandidate(
        String atsType,       // WORKDAY | GREENHOUSE | ASHBY | SMARTRECRUITERS
        String companyName,
        String careerUrl,
        String tenant,         // Workday-only
        String cluster,        // Workday-only
        String site) {         // Workday-only
}
