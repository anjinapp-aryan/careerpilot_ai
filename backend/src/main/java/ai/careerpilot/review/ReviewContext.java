package ai.careerpilot.review;

import ai.careerpilot.domain.ApplicationPackage;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.domain.ResumeAtsAnalysis;
import ai.careerpilot.domain.ResumeTailoring;
import ai.careerpilot.learning.api.LearningExplainContextService.LearningExplainContext;

/**
 * Phase 7.12 — the already-computed inputs a review run operates on, gathered once by the pipeline and
 * handed to every (pure, stateless) reviewer. Reviewers read these; they never fetch, re-score, or
 * mutate. Any field may be null when its upstream engine is dark or produced nothing — reviewers must
 * degrade gracefully (a missing artifact lowers the score / raises a flag, never throws).
 */
public record ReviewContext(ApplicationPackage pkg, ResumeTailoring tailoring, ResumeAtsAnalysis ats,
                            JobRecommendation recommendation, boolean companyResearchAvailable,
                            LearningExplainContext learning, Job job) {
}
