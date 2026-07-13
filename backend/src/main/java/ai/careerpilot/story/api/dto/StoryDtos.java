package ai.careerpilot.story.api.dto;

import ai.careerpilot.domain.StarStory;
import ai.careerpilot.domain.StoryAnalytics;
import ai.careerpilot.domain.StoryRecommendation;
import ai.careerpilot.domain.StoryUsage;
import ai.careerpilot.domain.StoryVersion;
import ai.careerpilot.story.StoryStatus;
import ai.careerpilot.story.StorySource;
import ai.careerpilot.story.StoryType;

import java.time.Instant;
import java.util.UUID;

/** Phase 7.15 — response DTOs for the STAR Story API (entities never leave the service layer). */
public final class StoryDtos {

    private StoryDtos() {}

    public record StoryResponse(UUID id, String title, StoryType storyType, StoryStatus status, StorySource source,
                                String situation, String task, String action, String result, String reflection,
                                String lessonsLearned, String skillsUsed, String technologiesUsed,
                                String competencies, String businessImpact, String evidence,
                                Integer confidenceScore, Integer qualityScore, String qualityBreakdown,
                                String improvementSuggestions, String missingSections, Integer currentVersion,
                                Instant createdAt, Instant updatedAt) {
        public static StoryResponse from(StarStory s) {
            return new StoryResponse(s.getId(), s.getTitle(), s.getStoryType(), s.getStatus(), s.getSource(),
                    s.getSituation(), s.getTask(), s.getAction(), s.getResult(), s.getReflection(),
                    s.getLessonsLearned(), s.getSkillsUsed(), s.getTechnologiesUsed(), s.getCompetencies(),
                    s.getBusinessImpact(), s.getEvidence(), s.getConfidenceScore(), s.getQualityScore(),
                    s.getQualityBreakdown(), s.getImprovementSuggestions(), s.getMissingSections(),
                    s.getCurrentVersion(), s.getCreatedAt(), s.getUpdatedAt());
        }
    }

    public record StorySummary(UUID id, String title, StoryType storyType, StoryStatus status,
                               Integer qualityScore, Instant updatedAt) {
        public static StorySummary from(StarStory s) {
            return new StorySummary(s.getId(), s.getTitle(), s.getStoryType(), s.getStatus(),
                    s.getQualityScore(), s.getUpdatedAt());
        }
    }

    public record VersionResponse(UUID id, Integer version, String changeSummary, String source, Instant createdAt) {
        public static VersionResponse from(StoryVersion v) {
            return new VersionResponse(v.getId(), v.getVersion(), v.getChangeSummary(), v.getSource(), v.getCreatedAt());
        }
    }

    public record UsageResponse(UUID id, UUID starStoryId, String companyName, String targetRole,
                                String interviewRound, String question, String outcome, Instant usedAt) {
        public static UsageResponse from(StoryUsage u) {
            return new UsageResponse(u.getId(), u.getStarStoryId(), u.getCompanyName(), u.getTargetRole(),
                    u.getInterviewRound(), u.getQuestion(), u.getOutcome(), u.getUsedAt());
        }
    }

    public record RecommendationResponse(UUID id, UUID starStoryId, String storyTitle, String companyName,
                                         String targetRole, String question, Integer matchScore, String reason) {
        public static RecommendationResponse from(StoryRecommendation r, String storyTitle) {
            return new RecommendationResponse(r.getId(), r.getStarStoryId(), storyTitle, r.getCompanyName(),
                    r.getTargetRole(), r.getQuestion(), r.getMatchScore(), r.getReason());
        }
    }

    public record AnalyticsResponse(Integer totalStories, Integer avgQualityScore, String byCategory,
                                    String byCompetency, Integer usageCount, Integer recommendationCount,
                                    Instant lastComputedAt) {
        public static AnalyticsResponse from(StoryAnalytics a) {
            return new AnalyticsResponse(a.getTotalStories(), a.getAvgQualityScore(), a.getByCategory(),
                    a.getByCompetency(), a.getUsageCount(), a.getRecommendationCount(), a.getLastComputedAt());
        }
    }

    public record GenerateRequest(StoryType storyType, String title, String hint) {}
    public record ManualCreateRequest(String title, StoryType storyType, String situation, String task,
                                      String action, String result, String reflection, String lessonsLearned,
                                      String skillsUsed, String technologiesUsed, String businessImpact,
                                      String evidence, Integer confidenceScore) {}
    public record ImproveRequest(String feedback) {}
    public record RateRequest(Integer confidenceScore) {}
    public record RecommendRequest(String companyName, String targetRole, String question) {}
    public record UsageRequest(UUID storyId, String companyName, String targetRole, String interviewRound,
                               String question, String outcome) {}
}
