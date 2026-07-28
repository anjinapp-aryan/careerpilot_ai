package ai.careerpilot.jobdiscovery.international;

import ai.careerpilot.api.dto.InternationalJobDtos.CountryJobCount;
import ai.careerpilot.api.dto.InternationalJobDtos.CountrySalary;
import ai.careerpilot.api.dto.InternationalJobDtos.DashboardDto;
import ai.careerpilot.api.dto.InternationalJobDtos.PipelineFunnel;
import ai.careerpilot.api.dto.InternationalJobDtos.SkillFamilyCount;
import ai.careerpilot.domain.Application;
import ai.careerpilot.domain.InternationalJobRanking;
import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.JobTaxonomy;
import ai.careerpilot.jobdiscovery.scope.InternationalScopeStrategy;
import ai.careerpilot.jobdiscovery.scope.JobScopeStrategyResolver;
import ai.careerpilot.repo.ApplicationRepository;
import ai.careerpilot.repo.InternationalJobRankingRepository;
import ai.careerpilot.repo.JobRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * International Job Discovery Engine, Phase 1 — aggregate stats over the current user's own
 * {@code international_job_ranking} rows (not a global platform-wide dashboard, and not a new
 * analytics pipeline — plain in-memory aggregation over already-small, per-user result sets,
 * mirroring the simple-aggregate style used elsewhere in this codebase, e.g. {@code
 * JobRepository#countByCountry}).
 */
@Service
public class InternationalDashboardService {

    private static final int TOP_N = 10;
    /** Same "Recommended" bar as JobMatchingService's default gateMinScore, applied to rankScore. */
    private static final int RECOMMENDED_THRESHOLD = 70;

    private final InternationalJobRankingRepository rankings;
    private final JobRepository jobs;
    private final ApplicationRepository applications;
    private final JobScopeStrategyResolver scopeResolver;
    private final JobTaxonomy taxonomy;

    public InternationalDashboardService(InternationalJobRankingRepository rankings,
                                          JobRepository jobs,
                                          ApplicationRepository applications,
                                          JobScopeStrategyResolver scopeResolver,
                                          JobTaxonomy taxonomy) {
        this.rankings = rankings;
        this.jobs = jobs;
        this.applications = applications;
        this.scopeResolver = scopeResolver;
        this.taxonomy = taxonomy;
    }

    public DashboardDto forUser(UUID userId) {
        List<InternationalJobRanking> ranked = rankings.findByUserIdOrderByRankScoreDesc(userId);
        if (ranked.isEmpty()) return DashboardDto.empty();

        List<UUID> jobIds = ranked.stream().map(InternationalJobRanking::getJobId).toList();
        Map<UUID, Job> jobById = jobs.findAllById(jobIds).stream()
                .collect(Collectors.toMap(Job::getId, j -> j));

        DashboardDto dto = new DashboardDto(
                topCountries(ranked),
                avgSalaryByCountry(ranked, jobById),
                visaSponsoredCount(ranked, jobById),
                ranked.size(),
                techHeatmap(ranked, jobById),
                pipelineFunnel(userId, ranked, jobIds));
        return dto;
    }

    private List<CountryJobCount> topCountries(List<InternationalJobRanking> ranked) {
        Map<String, List<InternationalJobRanking>> byCountry = ranked.stream()
                .filter(r -> r.getCountryCode() != null)
                .collect(Collectors.groupingBy(InternationalJobRanking::getCountryCode));
        return byCountry.entrySet().stream()
                .map(e -> new CountryJobCount(e.getKey(), e.getValue().size(),
                        e.getValue().stream().mapToInt(InternationalJobRanking::getRankScore).average().orElse(0)))
                .sorted(Comparator.comparingLong(CountryJobCount::jobCount).reversed())
                .limit(TOP_N)
                .toList();
    }

    private List<CountrySalary> avgSalaryByCountry(List<InternationalJobRanking> ranked, Map<UUID, Job> jobById) {
        Map<String, List<Job>> byCountry = ranked.stream()
                .filter(r -> r.getCountryCode() != null)
                .map(r -> jobById.get(r.getJobId()))
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(j -> countryCodeFor(j, ranked)));
        List<CountrySalary> out = new ArrayList<>();
        for (Map.Entry<String, List<Job>> e : byCountry.entrySet()) {
            if (e.getKey() == null) continue;
            List<BigDecimal> salaries = e.getValue().stream()
                    .map(InternationalDashboardService::midpointSalary)
                    .filter(Objects::nonNull)
                    .toList();
            if (salaries.isEmpty()) continue;
            double avg = salaries.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
            String currency = e.getValue().stream().map(Job::getCurrency).filter(Objects::nonNull).findFirst().orElse(null);
            out.add(new CountrySalary(e.getKey(), avg, currency));
        }
        return out;
    }

    private static String countryCodeFor(Job job, List<InternationalJobRanking> ranked) {
        return ranked.stream().filter(r -> r.getJobId().equals(job.getId()))
                .map(InternationalJobRanking::getCountryCode).findFirst().orElse(null);
    }

    private static BigDecimal midpointSalary(Job job) {
        BigDecimal min = job.getSalaryMin();
        BigDecimal max = job.getSalaryMax();
        if (min != null && max != null) return min.add(max).divide(BigDecimal.valueOf(2));
        return min != null ? min : max;
    }

    private long visaSponsoredCount(List<InternationalJobRanking> ranked, Map<UUID, Job> jobById) {
        return ranked.stream()
                .map(r -> jobById.get(r.getJobId()))
                .filter(Objects::nonNull)
                .filter(j -> Boolean.TRUE.equals(j.getSponsorshipAvailable()))
                .count();
    }

    private List<SkillFamilyCount> techHeatmap(List<InternationalJobRanking> ranked, Map<UUID, Job> jobById) {
        Map<String, Long> counts = new HashMap<>();
        for (InternationalJobRanking r : ranked) {
            Job job = jobById.get(r.getJobId());
            if (job == null) continue;
            String haystack = ((job.getTitle() == null ? "" : job.getTitle()) + " "
                    + (job.getDescription() == null ? "" : job.getDescription()) + " "
                    + (job.getSkills() == null ? "" : job.getSkills())).toLowerCase();
            for (String family : taxonomy.skillFamiliesInText(haystack)) {
                counts.merge(family, 1L, Long::sum);
            }
        }
        return counts.entrySet().stream()
                .map(e -> new SkillFamilyCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(SkillFamilyCount::jobCount).reversed())
                .limit(TOP_N)
                .toList();
    }

    private PipelineFunnel pipelineFunnel(UUID userId, List<InternationalJobRanking> ranked, List<UUID> rankedJobIds) {
        long discovered = discoveredPoolSize(userId);
        long eligible = ranked.size();
        long recommended = ranked.stream().filter(r -> r.getRankScore() >= RECOMMENDED_THRESHOLD).count();
        Set<UUID> rankedIdSet = new HashSet<>(rankedJobIds);
        long applied = applications.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(Application::getJobId)
                .filter(rankedIdSet::contains)
                .distinct()
                .count();
        return new PipelineFunnel(discovered, eligible, recommended, applied);
    }

    private long discoveredPoolSize(UUID userId) {
        List<String> countries = scopeResolver.forScope(InternationalScopeStrategy.SCOPE).resolveCountries(userId);
        if (countries.isEmpty()) return 0;
        List<String> countriesLower = countries.stream().map(String::toLowerCase).distinct().toList();
        return jobs.findDiscoveredInCountries(countriesLower, null, null, null, null, PageRequest.of(0, 1))
                .getTotalElements();
    }
}
