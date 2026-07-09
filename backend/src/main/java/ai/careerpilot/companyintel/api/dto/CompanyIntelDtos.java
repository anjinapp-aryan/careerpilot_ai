package ai.careerpilot.companyintel.api.dto;

import ai.careerpilot.companyintel.analyzer.CompanyInsight;
import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.domain.CompanyKnowledgeVersion;
import ai.careerpilot.domain.CompanyRelationship;
import ai.careerpilot.domain.CompanyTimelineEvent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Phase 7.13 — response DTOs for the Company Intelligence API (entities never leave the service layer). */
public final class CompanyIntelDtos {

    private CompanyIntelDtos() {}

    public record CompanyScores(Integer qualityScore, Integer interviewDifficulty, Integer hiringProbability,
                                Integer resumeCompatibility, Integer technologyMatch, Integer careerGrowth,
                                Integer salaryPotential, Integer cultureMatch, Integer learningValue,
                                Integer longTermOpportunity) {
        public static CompanyScores from(CompanyKnowledge k) {
            return new CompanyScores(k.getQualityScore(), k.getInterviewDifficulty(), k.getHiringProbability(),
                    k.getResumeCompatibility(), k.getTechnologyMatch(), k.getCareerGrowth(),
                    k.getSalaryPotential(), k.getCultureMatch(), k.getLearningValue(), k.getLongTermOpportunity());
        }
    }

    public record CompanyResponse(UUID id, String companyName, String industry, Integer knowledgeVersion,
                                  String lastSource, Instant firstDiscoveredAt, Instant updatedAt,
                                  CompanyScores scores, Map<String, String> scoreExplanations,
                                  Map<String, String> knowledge, List<InsightResponse> insights) {
        public static CompanyResponse from(CompanyKnowledge k, Map<String, String> sections,
                                           Map<String, String> explanations, List<CompanyInsight> insights) {
            return new CompanyResponse(k.getId(), k.getCompanyName(), k.getIndustry(), k.getKnowledgeVersion(),
                    k.getLastSource(), k.getFirstDiscoveredAt(), k.getUpdatedAt(),
                    CompanyScores.from(k), explanations, sections,
                    insights.stream().map(InsightResponse::from).toList());
        }
    }

    public record CompanySummary(UUID id, String companyName, String industry, Integer qualityScore,
                                 Integer hiringProbability, Integer knowledgeVersion, Instant updatedAt) {
        public static CompanySummary from(CompanyKnowledge k) {
            return new CompanySummary(k.getId(), k.getCompanyName(), k.getIndustry(), k.getQualityScore(),
                    k.getHiringProbability(), k.getKnowledgeVersion(), k.getUpdatedAt());
        }
    }

    public record InsightResponse(String kind, String headline, String detail) {
        public static InsightResponse from(CompanyInsight i) {
            return new InsightResponse(i.kind(), i.headline(), i.detail());
        }
    }

    public record TimelineEventResponse(UUID id, String eventType, UUID refId, String detail, Instant occurredAt) {
        public static TimelineEventResponse from(CompanyTimelineEvent e) {
            return new TimelineEventResponse(e.getId(), e.getEventType(), e.getRefId(), e.getDetail(), e.getOccurredAt());
        }
    }

    public record VersionResponse(UUID id, Integer version, String changedSections, String source, Instant createdAt) {
        public static VersionResponse from(CompanyKnowledgeVersion v) {
            return new VersionResponse(v.getId(), v.getVersion(), v.getChangedSections(), v.getSource(), v.getCreatedAt());
        }
    }

    public record RelationshipResponse(String relationType, String target, Integer weight, String evidence) {
        public static RelationshipResponse from(CompanyRelationship r) {
            return new RelationshipResponse(r.getRelationType(), r.getTarget(), r.getWeight(), r.getEvidence());
        }
    }

    public record GraphResponse(List<CompanySummary> nodes, Map<String, List<RelationshipResponse>> edgesByCompany) {}

    public record CompareResponse(CompanySummary a, CompanySummary b, int similarity,
                                  Map<String, List<String>> knowledgeDiff) {}

    public record StatisticsResponse(long companies, long knowledgeVersions, long timelineEvents,
                                     long graphEdges, List<CompanySummary> topCompanies,
                                     Map<String, Long> technologyDistribution,
                                     Map<String, Long> industryDistribution) {
        public static Map<String, Long> topCounts(Map<String, Long> raw, int limit) {
            Map<String, Long> out = new LinkedHashMap<>();
            raw.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(limit)
                    .forEach(e -> out.put(e.getKey(), e.getValue()));
            return out;
        }
    }
}
