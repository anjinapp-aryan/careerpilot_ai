package ai.careerpilot.api.dto;

import ai.careerpilot.domain.CountryIntelligence;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.SupportedCountry;
import ai.careerpilot.domain.InternationalJobRanking;

import java.util.List;

/**
 * International Job Discovery Engine, Phase 1 — response shapes for {@code
 * InternationalJobController}. All additive; nothing here replaces {@code
 * JobRecommendationDtos}/{@code Job} responses used by the existing endpoints.
 */
public final class InternationalJobDtos {

    private InternationalJobDtos() {}

    public record SupportedCountryDto(String countryCode, String displayName, String tier, String searchPriority) {
        public static SupportedCountryDto from(SupportedCountry c) {
            return new SupportedCountryDto(c.getCountryCode(), c.getDisplayName(), c.getTier().name(),
                    c.getSearchPriority() == null ? null : c.getSearchPriority().name());
        }
    }

    public record CountryIntelligenceDto(String countryCode, int visaProbabilityScore,
                                         int relocationDifficultyScore, int languageRequirementScore,
                                         int costOfLivingIndex, int expectedSavingsScore,
                                         int jobStabilityScore, int techMarketScore,
                                         int principalEngineerGrowthScore, int aiMarketScore,
                                         String sourceNote, Integer languageFriendlyScore, String industryFitJson) {
        public static CountryIntelligenceDto from(CountryIntelligence c) {
            return new CountryIntelligenceDto(c.getCountryCode(), c.getVisaProbabilityScore(),
                    c.getRelocationDifficultyScore(), c.getLanguageRequirementScore(),
                    c.getCostOfLivingIndex(), c.getExpectedSavingsScore(), c.getJobStabilityScore(),
                    c.getTechMarketScore(), c.getPrincipalEngineerGrowthScore(), c.getAiMarketScore(),
                    c.getSourceNote(), c.getLanguageFriendlyScore(), c.getIndustryFitJson());
        }
    }

    public record RankingDto(int rankScore, int skillScore, int visaProbabilityScore, int salaryScore,
                             int careerGrowthScore, int companyStabilityScore, int remoteFlexibilityScore,
                             int principalEngineerGrowthScore, int aiTechStackScore,
                             String countryCode, String tier) {
        public static RankingDto from(InternationalJobRanking r) {
            return new RankingDto(r.getRankScore(), r.getSkillScore(), r.getVisaProbabilityScore(),
                    r.getSalaryScore(), r.getCareerGrowthScore(), r.getCompanyStabilityScore(),
                    r.getRemoteFlexibilityScore(), r.getPrincipalEngineerGrowthScore(),
                    r.getAiTechStackScore(), r.getCountryCode(), r.getTier());
        }
    }

    public record RankedJobDto(Job job, RankingDto ranking, CountryIntelligenceDto countryIntelligence) {}

    public record RankedJobsPageDto(List<RankedJobDto> content, long totalElements, int totalPages,
                                    int number, int size) {
        public static RankedJobsPageDto empty(int page, int size) {
            return new RankedJobsPageDto(List.of(), 0, 0, page, size);
        }
    }

    public record CountryJobCount(String countryCode, long jobCount, Double avgMatchScore) {}

    public record CountrySalary(String countryCode, Double avgSalary, String currency) {}

    public record SkillFamilyCount(String skillFamily, long jobCount) {}

    public record PipelineFunnel(long discovered, long eligible, long recommended, long applied) {}

    public record DashboardDto(List<CountryJobCount> topCountries, List<CountrySalary> avgSalaryByCountry,
                               long visaSponsoredCount, long totalInternationalJobs,
                               List<SkillFamilyCount> techHeatmap, PipelineFunnel pipelineFunnel) {
        public static DashboardDto empty() {
            return new DashboardDto(List.of(), List.of(), 0, 0, List.of(),
                    new PipelineFunnel(0, 0, 0, 0));
        }
    }
}
