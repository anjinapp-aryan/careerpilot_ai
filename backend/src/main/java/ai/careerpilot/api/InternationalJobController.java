package ai.careerpilot.api;

import ai.careerpilot.api.dto.InternationalJobDtos.CountryIntelligenceDto;
import ai.careerpilot.api.dto.InternationalJobDtos.DashboardDto;
import ai.careerpilot.api.dto.InternationalJobDtos.RankedJobDto;
import ai.careerpilot.api.dto.InternationalJobDtos.RankedJobsPageDto;
import ai.careerpilot.api.dto.InternationalJobDtos.RankingDto;
import ai.careerpilot.api.dto.InternationalJobDtos.SupportedCountryDto;
import ai.careerpilot.domain.InternationalJobRanking;
import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.international.CountryIntelligenceService;
import ai.careerpilot.jobdiscovery.international.InternationalDashboardService;
import ai.careerpilot.jobdiscovery.international.SupportedCountryService;
import ai.careerpilot.repo.InternationalJobRankingRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * International Job Discovery Engine, Phase 1 — all endpoints additive: {@code GET
 * /api/jobs/discovered} and {@code GET /api/jobs/recommended} are untouched byte-for-byte.
 * {@code /ranked} composes (never replaces) the existing job shape with the new country-tier
 * ranking data, and returns an empty page (never errors) when {@code
 * career.international.ranking.enabled} is off or the user has no ranking rows yet — the frontend
 * falls back to the plain {@code /api/jobs/discovered?scope=international} view in that case.
 */
@RestController
@RequestMapping("/api/jobs/international")
public class InternationalJobController {

    private final SupportedCountryService supportedCountries;
    private final CountryIntelligenceService countryIntelligence;
    private final InternationalJobRankingRepository rankings;
    private final JobRepository jobs;
    private final InternationalDashboardService dashboard;

    public InternationalJobController(SupportedCountryService supportedCountries,
                                       CountryIntelligenceService countryIntelligence,
                                       InternationalJobRankingRepository rankings,
                                       JobRepository jobs,
                                       InternationalDashboardService dashboard) {
        this.supportedCountries = supportedCountries;
        this.countryIntelligence = countryIntelligence;
        this.rankings = rankings;
        this.jobs = jobs;
        this.dashboard = dashboard;
    }

    @GetMapping("/countries")
    public List<SupportedCountryDto> countries(AuthenticatedUser user) {
        return supportedCountries.listActive().stream().map(SupportedCountryDto::from).toList();
    }

    @GetMapping("/intelligence/{countryCode}")
    public CountryIntelligenceDto intelligence(AuthenticatedUser user, @PathVariable String countryCode) {
        return countryIntelligence.forCountry(countryCode).map(CountryIntelligenceDto::from)
                .orElseThrow(() -> new java.util.NoSuchElementException("No country intelligence for: " + countryCode));
    }

    @GetMapping("/ranked")
    public RankedJobsPageDto ranked(AuthenticatedUser user,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        int pageSize = Math.min(Math.max(size, 1), 100);
        List<InternationalJobRanking> all = rankings.findByUserIdOrderByRankScoreDesc(user.userId());
        if (all.isEmpty()) return RankedJobsPageDto.empty(page, pageSize);

        int from = Math.min(page * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        List<InternationalJobRanking> pageRows = all.subList(from, to);

        Map<UUID, Job> jobById = jobs.findAllById(pageRows.stream().map(InternationalJobRanking::getJobId).toList())
                .stream().collect(Collectors.toMap(Job::getId, j -> j));

        List<RankedJobDto> content = pageRows.stream()
                .map(r -> {
                    Job job = jobById.get(r.getJobId());
                    CountryIntelligenceDto intel = r.getCountryCode() == null ? null
                            : countryIntelligence.forCountry(r.getCountryCode()).map(CountryIntelligenceDto::from).orElse(null);
                    return new RankedJobDto(job, RankingDto.from(r), intel);
                })
                .filter(d -> d.job() != null)
                .toList();

        int totalPages = (int) Math.ceil(all.size() / (double) pageSize);
        return new RankedJobsPageDto(content, all.size(), totalPages, page, pageSize);
    }

    @GetMapping("/dashboard")
    public DashboardDto dashboardStats(AuthenticatedUser user) {
        return dashboard.forUser(user.userId());
    }
}
