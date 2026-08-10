package ai.careerpilot.submission.reuse;

import ai.careerpilot.domain.ApplicationSubmissionAnswer;
import ai.careerpilot.domain.ApplicationSubmissionSession;
import ai.careerpilot.repo.ApplicationSubmissionAnswerRepository;
import ai.careerpilot.repo.ApplicationSubmissionSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Eliminate Repeated Validation & Package Preparation — {@link ApplicationReuseResolver}. Cases
 * mirror the master prompt's numbered test scenarios (§24), scoped to what this codebase's Step 6
 * (field mapping + AI-generated question answers) can actually reuse: browser-level form
 * discovery (§4/§7 of the prompt) happens later, live, inside execution, and is unchanged.
 */
class ApplicationReuseResolverTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID currentSessionId = UUID.randomUUID();
    private final UUID resumeTailoringId = UUID.randomUUID();
    private final UUID packageId = UUID.randomUUID();
    private final UUID companyKnowledgeId = UUID.randomUUID();
    private final UUID starStoryId = UUID.randomUUID();

    private ApplicationSubmissionSessionRepository sessions;
    private ApplicationSubmissionAnswerRepository answers;
    private ApplicationReuseResolver resolver;

    private final ApplicationReuseResolver.Basis basis =
            new ApplicationReuseResolver.Basis(resumeTailoringId, packageId, companyKnowledgeId, starStoryId);

    @BeforeEach
    void setUp() {
        sessions = mock(ApplicationSubmissionSessionRepository.class);
        answers = mock(ApplicationSubmissionAnswerRepository.class);
        resolver = new ApplicationReuseResolver(sessions, answers, 24);
    }

    private ApplicationSubmissionSession priorSession(UUID resumeId, UUID pkgId, UUID companyId, UUID storyId,
                                                       Instant updatedAt, boolean stepSixRan) {
        return ApplicationSubmissionSession.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .status(ApplicationSubmissionSession.STATUS_COMPLETED)
                .resumeTailoringId(resumeId).applicationPackageId(pkgId)
                .companyKnowledgeId(companyId).starStoryId(storyId)
                .answersReuseDecision(stepSixRan ? "FULL_BUILD" : null)
                .createdAt(updatedAt).updatedAt(updatedAt)
                .build();
    }

    private void withAnswers(UUID sessionId) {
        when(answers.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(
                List.of(ApplicationSubmissionAnswer.builder().id(UUID.randomUUID()).sessionId(sessionId)
                        .questionText("Why are you interested?").questionCategory("WHY_ROLE")
                        .answerText("Because...").build()));
    }

    // Case 1 — first application: no prior session at all.
    @Test
    void firstApplicationIsFullBuild() {
        when(sessions.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(List.of());

        var decision = resolver.resolve(userId, jobId, currentSessionId, basis);

        assertThat(decision.reason()).isEqualTo(AnswerReuseDecision.FULL_BUILD);
        assertThat(decision.sourceSession()).isEmpty();
    }

    // Case 2/3 — second (and fifth) application to the same job, nothing changed: reused.
    @Test
    void secondApplicationSameEverythingReusesAnswers() {
        ApplicationSubmissionSession prior = priorSession(resumeTailoringId, packageId, companyKnowledgeId,
                starStoryId, Instant.now().minus(1, ChronoUnit.HOURS), true);
        when(sessions.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(List.of(prior));
        withAnswers(prior.getId());

        var decision = resolver.resolve(userId, jobId, currentSessionId, basis);

        assertThat(decision.reason()).isEqualTo(AnswerReuseDecision.REUSED);
        assertThat(decision.sourceSession()).contains(prior);
    }

    @Test
    void fifthApplicationStillReusesAgainstTheMostRecentCompatiblePriorSession() {
        // Four prior sessions, all identical basis — the resolver picks the most recent one.
        ApplicationSubmissionSession oldest = priorSession(resumeTailoringId, packageId, companyKnowledgeId,
                starStoryId, Instant.now().minus(4, ChronoUnit.HOURS), true);
        ApplicationSubmissionSession newest = priorSession(resumeTailoringId, packageId, companyKnowledgeId,
                starStoryId, Instant.now().minus(1, ChronoUnit.HOURS), true);
        when(sessions.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId))
                .thenReturn(List.of(newest, oldest)); // repo contract: newest first
        withAnswers(newest.getId());

        var decision = resolver.resolve(userId, jobId, currentSessionId, basis);

        assertThat(decision.reason()).isEqualTo(AnswerReuseDecision.REUSED);
        assertThat(decision.sourceSession()).contains(newest);
    }

    // Case 4 — validation/answers expired past the TTL: rebuilt.
    @Test
    void expiredPriorSessionForcesRebuild() {
        ApplicationSubmissionSession stale = priorSession(resumeTailoringId, packageId, companyKnowledgeId,
                starStoryId, Instant.now().minus(48, ChronoUnit.HOURS), true);
        when(sessions.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(List.of(stale));

        var decision = resolver.resolve(userId, jobId, currentSessionId, basis);

        assertThat(decision.reason()).isEqualTo(AnswerReuseDecision.REBUILT_EXPIRED);
        assertThat(decision.sourceSession()).isEmpty();
    }

    // Case 5 — resume changed since the prior session: rebuild, reason says why.
    @Test
    void resumeChangedForcesRebuildWithExplicitReason() {
        ApplicationSubmissionSession prior = priorSession(UUID.randomUUID() /* different tailoring */, packageId,
                companyKnowledgeId, starStoryId, Instant.now().minus(1, ChronoUnit.HOURS), true);
        when(sessions.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(List.of(prior));

        var decision = resolver.resolve(userId, jobId, currentSessionId, basis);

        assertThat(decision.reason()).isEqualTo(AnswerReuseDecision.REBUILT_RESUME_CHANGED);
    }

    // Case 6 — profile changed. In this codebase a profile edit surfaces as a new package version
    // (ApplicationPackageService bumps packageId), so it is caught via REBUILT_PACKAGE_CHANGED.
    @Test
    void profileChangeSurfacesAsPackageChangeAndForcesRebuild() {
        ApplicationSubmissionSession prior = priorSession(resumeTailoringId, UUID.randomUUID() /* different package */,
                companyKnowledgeId, starStoryId, Instant.now().minus(1, ChronoUnit.HOURS), true);
        when(sessions.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(List.of(prior));

        var decision = resolver.resolve(userId, jobId, currentSessionId, basis);

        assertThat(decision.reason()).isEqualTo(AnswerReuseDecision.REBUILT_PACKAGE_CHANGED);
    }

    // Case 7 — context (company brief / STAR story) changed: rebuild.
    @Test
    void contextChangeForcesRebuild() {
        ApplicationSubmissionSession prior = priorSession(resumeTailoringId, packageId,
                UUID.randomUUID() /* different company knowledge */, starStoryId,
                Instant.now().minus(1, ChronoUnit.HOURS), true);
        when(sessions.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(List.of(prior));

        var decision = resolver.resolve(userId, jobId, currentSessionId, basis);

        assertThat(decision.reason()).isEqualTo(AnswerReuseDecision.REBUILT_CONTEXT_CHANGED);
    }

    // Case 8 — different job (different fingerprint at the caller level) never even reaches this
    // resolver with the other job's history, since the caller always queries by the current jobId.
    @Test
    void aDifferentJobHasNoSharedHistorySoAlwaysFullBuilds() {
        UUID otherJobId = UUID.randomUUID();
        when(sessions.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, otherJobId)).thenReturn(List.of());

        var decision = resolver.resolve(userId, otherJobId, currentSessionId, basis);

        assertThat(decision.reason()).isEqualTo(AnswerReuseDecision.FULL_BUILD);
    }

    @Test
    void currentSessionIsNeverComparedAgainstItself() {
        // If the "current" session were somehow already in history (e.g. re-entrant call), it must
        // be excluded rather than trivially matching itself.
        ApplicationSubmissionSession self = priorSession(resumeTailoringId, packageId, companyKnowledgeId,
                starStoryId, Instant.now(), true);
        self.setId(currentSessionId);
        when(sessions.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(List.of(self));

        var decision = resolver.resolve(userId, jobId, currentSessionId, basis);

        assertThat(decision.reason()).isEqualTo(AnswerReuseDecision.FULL_BUILD);
    }

    @Test
    void aPriorSessionWhereStepSixNeverRanIsNotAReuseCandidate() {
        // answersReuseDecision == null means Step 6 never completed on that session (e.g. it failed
        // at validation) — nothing to copy.
        ApplicationSubmissionSession neverRanStepSix = priorSession(resumeTailoringId, packageId, companyKnowledgeId,
                starStoryId, Instant.now().minus(1, ChronoUnit.HOURS), false);
        when(sessions.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(List.of(neverRanStepSix));

        var decision = resolver.resolve(userId, jobId, currentSessionId, basis);

        assertThat(decision.reason()).isEqualTo(AnswerReuseDecision.FULL_BUILD);
    }

    @Test
    void compatiblePriorSessionWithNoPersistedAnswersFullBuildsRatherThanCopyingNothing() {
        ApplicationSubmissionSession prior = priorSession(resumeTailoringId, packageId, companyKnowledgeId,
                starStoryId, Instant.now().minus(1, ChronoUnit.HOURS), true);
        when(sessions.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(List.of(prior));
        when(answers.findBySessionIdOrderByCreatedAtAsc(prior.getId())).thenReturn(List.of());

        var decision = resolver.resolve(userId, jobId, currentSessionId, basis);

        assertThat(decision.reason()).isEqualTo(AnswerReuseDecision.FULL_BUILD);
    }

    // Case 10 — concurrent resolution for the same (user, job) never corrupts state: each call is a
    // pure read over its own repo snapshot, so two concurrent resolutions are simply two independent,
    // safely-computed decisions (no shared mutable state to race on).
    @Test
    void concurrentResolutionsForTheSameJobAreIndependentAndSafe() {
        ApplicationSubmissionSession prior = priorSession(resumeTailoringId, packageId, companyKnowledgeId,
                starStoryId, Instant.now().minus(1, ChronoUnit.HOURS), true);
        when(sessions.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(List.of(prior));
        withAnswers(prior.getId());
        UUID otherAttemptSessionId = UUID.randomUUID();

        CompletableFuture<ApplicationReuseResolver.Decision> a =
                CompletableFuture.supplyAsync(() -> resolver.resolve(userId, jobId, currentSessionId, basis));
        CompletableFuture<ApplicationReuseResolver.Decision> b =
                CompletableFuture.supplyAsync(() -> resolver.resolve(userId, jobId, otherAttemptSessionId, basis));

        assertThat(a.join().reason()).isEqualTo(AnswerReuseDecision.REUSED);
        assertThat(b.join().reason()).isEqualTo(AnswerReuseDecision.REUSED);
        assertThat(a.join().sourceSession()).isEqualTo(b.join().sourceSession());
    }
}
